package com.agnetix.harnax.router.proxy

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.AgentServiceClient
import com.agnetix.harnax.router.service.IdempotencyService
import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionAccessGuard
import com.agnetix.harnax.router.service.SessionEvictor
import com.agnetix.harnax.router.service.SessionMappingService
import com.agnetix.harnax.router.support.IdFormat
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service
class SessionRouterService(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
    private val idempotencyService: IdempotencyService,
    private val circuitBreaker: InstanceCircuitBreaker,
    private val agentServiceClient: AgentServiceClient,
    private val sessionEvictor: SessionEvictor,
    private val sessionAccessGuard: SessionAccessGuard,
    private val meterRegistry: MeterRegistry,
    @Value($$"${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
    @Value($$"${router.proxy.failover-max-retries:2}")
    private val failoverMaxRetries: Int,
) {

    private val log = LoggerFactory.getLogger(SessionRouterService::class.java)

    companion object {
        /**
         * Request attribute holding the instance this call was last placed on. The call log reads it
         * there because MDC — which carries the same value for the logs — belongs to the thread that
         * routed the call, not to the one that finishes the response.
         */
        const val ROUTED_INSTANCE_ATTR = "router.routedInstanceId"
    }

    private val mdcKeys = setOf("sessionId", "requestId", "instanceId")

    // Pre-cache hot Timer / Counter instances to avoid MeterRegistry lookups on every request.
    private lateinit var chatTimer: Timer
    private lateinit var chatOkCounter: Counter
    private lateinit var chatErrorCounter: Counter
    private lateinit var streamOkCounter: Counter
    private lateinit var streamErrorCounter: Counter

    // Failover counters are cached by (endpoint, attempt); the attempt index is dynamic.
    private val failoverCounterCache = ConcurrentHashMap<String, Counter>()

    init {
        initMeters()
    }

    @PostConstruct
    fun initMeters() {
        chatTimer = meterRegistry.timer("router.proxy.duration", "endpoint", "chat")
        chatOkCounter = meterRegistry.counter(
            "router.proxy.requests",
            "endpoint",
            "chat",
            "status",
            "ok",
        )
        chatErrorCounter = meterRegistry.counter(
            "router.proxy.requests",
            "endpoint",
            "chat",
            "status",
            "error",
        )
        streamOkCounter = meterRegistry.counter(
            "router.proxy.requests",
            "endpoint",
            "stream",
            "status",
            "ok",
        )
        streamErrorCounter = meterRegistry.counter(
            "router.proxy.requests",
            "endpoint",
            "stream",
            "status",
            "error",
        )
    }

    private fun failoverCounter(endpoint: String, attempt: Int): Counter = failoverCounterCache.computeIfAbsent("$endpoint:$attempt") {
        meterRegistry.counter(
            "router.failover.count",
            "endpoint",
            endpoint,
            "attempt",
            attempt.toString(),
        )
    }

    suspend fun proxyChatRequest(request: ChatAgentRequest): ResultVo<ChatResponse> {
        val sessionId = request.sessionId
        sessionAccessGuard.requireAccessible(sessionId)
        val requestId = getOrGenerateRequestId(request)
        // Only an id the client chose can identify a retry. A generated one is unique by definition,
        // so guarding it would cost a round trip and reject nothing.
        val dedupeKey = request.requestId.takeIf { it.isNotBlank() }
        var ownsSlot = false
        setMDC(sessionId, requestId)
        try {
            if (dedupeKey != null) {
                if (!idempotencyService.tryAcquire(dedupeKey)) {
                    log.warn("Duplicate request detected: $dedupeKey")
                    // 429 in the envelope, not a 500: the same request is already in flight, and the
                    // caller's own retry will succeed once it finishes.
                    return ResultVo.error(429, "Duplicate request: $dedupeKey")
                }
                ownsSlot = true
            }

            val sample = Timer.start()

            val result = try {
                executeWithRetry(sessionId, "chat") { targetInstance ->
                    agentServiceClient.chat(targetInstance.getBaseUrl(), request)
                }
            } catch (e: Exception) {
                // A call that dies with an exception is the loudest kind of failure, and until now it
                // never reached these meters: only an error *response* did.
                sample.stop(chatTimer)
                chatErrorCounter.increment()
                throw e
            }

            sample.stop(chatTimer)
            if (result.isSuccess()) {
                chatOkCounter.increment()
                sessionMappingService.refreshActiveTime(sessionId)
            } else {
                chatErrorCounter.increment()
            }
            log.info("[Router] proxyChatRequest final result for session=$sessionId, code=${result.code}, success=${result.isSuccess()}, contentLength=${result.data?.content?.length ?: 0}")
            return result
        } finally {
            // Hand the lease back before the caller could retry: it guards concurrent in-flight
            // duplicates for one request, not a replay window.
            dedupeKey?.let { if (ownsSlot) idempotencyService.release(it) }
            clearMDC()
        }
    }

    fun proxyStreamRequest(request: ChatAgentRequest): Flux<ChatEvent> {
        val sessionId = request.sessionId
        sessionAccessGuard.requireAccessible(sessionId)
        val requestId = getOrGenerateRequestId(request)
        // Note: MDC is not set here because the returned Flux is subscribed to and executed
        // on a Netty event loop thread, where the servlet thread's MDC is not visible.
        // Context (sessionId, requestId) is logged directly in each operator's messages.
        try {
            val instance = resolveInstanceBlocking(sessionId)
            log.info("Stream proxy for session=$sessionId, requestId=$requestId -> instance=${instance.instanceId}")

            return buildStreamFlux(sessionId, requestId, instance, request, attempt = 0, excluded = emptySet())
        } catch (e: Exception) {
            log.error("Failed to resolve instance for stream session=$sessionId, requestId=$requestId: ${e.message}", e)
            return Flux.just(
                ErrorChatEvent(
                    code = HarnaxErrorCode.ROUTER_NO_INSTANCE.code,
                    message = e.message ?: "No instance available",
                ),
                EndEventChatEvent(),
            )
        }
    }

    suspend fun proxyCommandRequest(request: CommandAgentRequest): ResultVo<CommandResponse> {
        val sessionId = request.sessionId
        sessionAccessGuard.requireAccessible(sessionId)
        setMDC(sessionId, null)
        try {
            val instance = resolveInstance(sessionId)
            MDC.put("instanceId", instance.instanceId)

            return executeWithRetry(sessionId, "command") { targetInstance ->
                agentServiceClient.command(targetInstance.getBaseUrl(), request)
            }.also {
                sessionMappingService.refreshActiveTime(sessionId)
            }
        } finally {
            clearMDC()
        }
    }

    fun proxyConfirmStreamRequest(request: ConfirmAgentRequest): Flux<ChatEvent> {
        val sessionId = request.sessionId
        sessionAccessGuard.requireAccessible(sessionId)
        // Note: MDC is not set here for the same thread-safety reasons as proxyStreamRequest.
        try {
            val instance = resolveInstanceBlocking(sessionId)
            log.info("Confirm stream proxy for session=$sessionId -> instance=${instance.instanceId}")

            return buildConfirmStreamFlux(sessionId, instance, request, attempt = 0, excluded = emptySet())
        } catch (e: Exception) {
            log.error("Failed to resolve instance for confirm stream session=$sessionId: ${e.message}", e)
            return Flux.just(
                ErrorChatEvent(
                    code = HarnaxErrorCode.ROUTER_NO_INSTANCE.code,
                    message = e.message ?: "No instance available",
                ),
                EndEventChatEvent(),
            )
        }
    }

    private fun buildConfirmStreamFlux(
        sessionId: String,
        instance: AgentInstance,
        request: ConfirmAgentRequest,
        attempt: Int,
        excluded: Set<String>,
    ): Flux<ChatEvent> {
        // A stream deliberately does not touch MDC — its events arrive on an agent's event loop — so
        // this is the only record of where it was sent.
        trackPlacement(instance)
        val delivered = AtomicBoolean(false)
        return agentServiceClient.confirmStream(instance.getBaseUrl(), request)
            .doOnNext { delivered.set(true) }
            .doOnComplete {
                circuitBreaker.recordSuccess(instance.instanceId)
                streamOkCounter.increment()
                sessionMappingService.refreshActiveTime(sessionId)
            }
            .doOnCancel {
                log.info("Confirm stream cancelled by client for session=$sessionId")
            }
            .onErrorResume { e ->
                if (isRetryableError(e)) {
                    circuitBreaker.recordFailure(instance.instanceId)
                }
                streamErrorCounter.increment()
                log.error("Confirm stream proxy error for session=$sessionId on ${instance.instanceId}: ${e.message}", e)

                if (isConnectivityError(e) && attempt < failoverMaxRetries && !delivered.get()) {
                    return@onErrorResume tryConfirmStreamFailover(
                        sessionId,
                        request,
                        attempt + 1,
                        excluded + instance.instanceId,
                        instance.instanceId,
                    )
                }

                Flux.just(
                    ErrorChatEvent(
                        code = HarnaxErrorCode.ROUTER_PROXY_ERROR.code,
                        message = e.message ?: "Failed to reach agent-service",
                    ),
                    EndEventChatEvent(),
                )
            }
    }

    private fun tryConfirmStreamFailover(
        sessionId: String,
        request: ConfirmAgentRequest,
        attempt: Int,
        excluded: Set<String>,
        leftBehind: String,
    ): Flux<ChatEvent> {
        return Flux.defer {
            try {
                val skip = placementExclusions(excluded)
                val newInstanceId = sessionMappingService.rerouteSession(sessionId, skip)
                val newInstance = instanceRegistry.getInstance(newInstanceId)

                if (newInstance == null || !circuitBreaker.allowRequest(newInstanceId)) {
                    return@defer Flux.just(
                        ErrorChatEvent(
                            code = HarnaxErrorCode.ROUTER_NO_INSTANCE.code,
                            message = "No available instance for confirm failover",
                        ),
                        EndEventChatEvent(),
                    )
                }

                log.info("Confirm stream failover attempt $attempt for session $sessionId -> ${newInstance.instanceId}")
                failoverCounter("confirm-stream", attempt).increment()
                sessionEvictor.requestEviction(sessionId, leftBehind)
                buildConfirmStreamFlux(sessionId, newInstance, request, attempt, skip)
            } catch (e: Exception) {
                log.error("Confirm stream failover attempt $attempt failed for session $sessionId: ${e.message}", e)
                Flux.just(
                    ErrorChatEvent(
                        code = HarnaxErrorCode.ROUTER_PROXY_ERROR.code,
                        message = "Failover failed: ${e.message}",
                    ),
                    EndEventChatEvent(),
                )
            }
        }
    }

    /**
     * Clears the session on the agent that holds it. A session this router has never placed has no
     * sandbox to clear, so deleting it succeeds without asking anyone.
     */
    suspend fun proxyClearSession(sessionId: String): ResultVo<String> {
        setMDC(sessionId, null)
        try {
            val instance = boundInstance(sessionId) ?: return ResultVo.success("Session $sessionId is not bound to any instance")
            return callBound(sessionId, "clearSession", instance) { target ->
                agentServiceClient.clearSession(target.getBaseUrl(), sessionId)
            }
        } finally {
            clearMDC()
        }
    }

    suspend fun proxyLoadHistory(sessionId: String): ResultVo<List<Any>> {
        setMDC(sessionId, null)
        try {
            val instance = boundInstance(sessionId) ?: return ResultVo.success(emptyList())
            return callBound(sessionId, "loadHistory", instance) { target ->
                agentServiceClient.loadHistory(target.getBaseUrl(), sessionId)
            }
        } finally {
            clearMDC()
        }
    }

    suspend fun proxyLoadPlans(sessionId: String): ResultVo<List<Any>> {
        setMDC(sessionId, null)
        try {
            val instance = boundInstance(sessionId) ?: return ResultVo.success(emptyList())
            return callBound(sessionId, "loadPlans", instance) { target ->
                agentServiceClient.loadPlans(target.getBaseUrl(), sessionId)
            }
        } finally {
            clearMDC()
        }
    }

    suspend fun proxyLoadCurrentPlan(sessionId: String): ResultVo<Any?> {
        setMDC(sessionId, null)
        try {
            val instance = boundInstance(sessionId) ?: return ResultVo.success(null)
            return callBound(sessionId, "loadCurrentPlan", instance) { target ->
                agentServiceClient.loadCurrentPlan(target.getBaseUrl(), sessionId)
            }
        } finally {
            clearMDC()
        }
    }

    // ---- Workspace proxy methods ----

    suspend fun proxyWorkspaceListFiles(sessionId: String, path: String): ResultVo<List<Map<String, Any>>> {
        setMDC(sessionId, null)
        try {
            val instance = boundInstance(sessionId) ?: return ResultVo.success(emptyList())
            return callBound(sessionId, "workspaceFiles", instance) { targetInstance ->
                agentServiceClient.workspaceListFiles(targetInstance.getBaseUrl(), sessionId, path)
            }
        } finally {
            clearMDC()
        }
    }

    suspend fun proxyWorkspaceReadFile(sessionId: String, path: String): ResultVo<Map<String, Any>> {
        setMDC(sessionId, null)
        try {
            val instance = boundInstance(sessionId)
                ?: return ResultVo.error(404, "Session $sessionId has no workspace: it is not bound to an agent instance")
            return callBound(sessionId, "workspaceRead", instance) { targetInstance ->
                agentServiceClient.workspaceReadFile(targetInstance.getBaseUrl(), sessionId, path)
            }
        } finally {
            clearMDC()
        }
    }

    /**
     * Workspace status of one or more sessions. The sessions of one caller share an instance, so the
     * first one picks it; a session with no instance reports nothing rather than being placed.
     */
    suspend fun proxyWorkspaceStatus(sessionIds: String): ResultVo<Map<String, Map<String, Any>>> {
        // The whole list travels to one agent, which answers for every id in it. Checking only the
        // first would leave the rest as a probe into another tenant's sessions.
        val ids = IdFormat.parseSessionIds(sessionIds)
        ids.forEach { sessionAccessGuard.requireAccessible(it) }
        val firstId = ids.first()
        setMDC(firstId, null)
        try {
            val instance = boundInstance(firstId) ?: return ResultVo.success(emptyMap())
            return callBound(firstId, "workspaceStatus", instance) { targetInstance ->
                agentServiceClient.workspaceStatus(targetInstance.getBaseUrl(), ids.joinToString(","))
            }
        } finally {
            clearMDC()
        }
    }

    /**
     * Proxy file upload request to agent-service.
     *
     * The file lands in the sandbox of the instance holding the session, so an unbound session has
     * nowhere for it to go: rerouting would have written it into a box the session never sees.
     */
    suspend fun proxyWorkspaceUpload(
        sessionId: String,
        path: String,
        fileName: String,
        fileBytes: ByteArray,
    ): ResultVo<Map<String, Any>> {
        setMDC(sessionId, null)
        try {
            val instance = boundInstance(sessionId)
                ?: return ResultVo.error(409, "Session $sessionId is not bound to an agent instance; send a message before uploading")
            return callBound(sessionId, "workspaceUpload", instance) { targetInstance ->
                agentServiceClient.workspaceUpload(targetInstance.getBaseUrl(), sessionId, path, fileName, fileBytes)
            }
        } finally {
            clearMDC()
        }
    }

    /**
     * Proxy file download request to agent-service.
     * Returns the raw response as ByteArray with content type.
     */
    suspend fun proxyWorkspaceDownload(
        sessionId: String,
        path: String,
    ): Pair<ByteArray, String>? {
        setMDC(sessionId, null)
        try {
            val instance = boundInstance(sessionId) ?: return null
            return callBound(sessionId, "workspaceDownload", instance) { targetInstance ->
                agentServiceClient.workspaceDownload(targetInstance.getBaseUrl(), sessionId, path)
            }
        } finally {
            clearMDC()
        }
    }

    /**
     * The instance this session is already bound to, or null when it never routed anywhere.
     *
     * Read-only endpoints use this instead of [resolveInstance]. The history, plans and files they
     * ask about live inside one agent's sandbox, so placing the session on a *different* agent cannot
     * make the read succeed — it answers from an empty box, and the caller can no longer tell "this
     * session has no history" from "its history is on an instance you did not ask". A session whose
     * instance was unregistered is reported as unbound, which is what it is.
     */
    private fun boundInstance(sessionId: String): AgentInstance? {
        // Every read-only path comes through here, which makes it the one place a session can be
        // checked once for all of them: a new endpoint that resolves a binding inherits the guard.
        sessionAccessGuard.requireAccessible(sessionId)
        return sessionMappingService.getInstanceId(sessionId)
            ?.let { instanceRegistry.getInstance(it) }
            .also { instance ->
                if (instance != null) {
                    MDC.put("instanceId", instance.instanceId)
                    trackPlacement(instance)
                }
            }
    }

    /**
     * One call to the instance that holds the session. Nothing is retried elsewhere: a second agent
     * does not have what the first one was asked for. The failure is reported to the caller as it is,
     * and only a failure that means "this agent is not answering" counts against its circuit.
     */
    private suspend fun <T> callBound(
        sessionId: String,
        endpoint: String,
        instance: AgentInstance,
        action: suspend (AgentInstance) -> T,
    ): T = try {
        action(instance).also { circuitBreaker.recordSuccess(instance.instanceId) }
    } catch (e: Exception) {
        log.error("Failed to proxy $endpoint for session $sessionId: ${describeProxyError(e, endpoint, instance)}", e)
        if (isRetryableError(e)) {
            circuitBreaker.recordFailure(instance.instanceId)
        }
        throw e
    }

    private suspend fun resolveInstance(sessionId: String): AgentInstance {
        return try {
            val existingInstanceId = sessionMappingService.getInstanceId(sessionId)

            if (existingInstanceId != null) {
                try {
                    val instance = instanceRegistry.getInstance(existingInstanceId)
                    if (instance != null &&
                        instance.isHealthy(heartbeatTimeoutMs) &&
                        !instance.isDraining()
                    ) {
                        return instance
                    }
                } catch (e: Exception) {
                    log.warn("Error checking instance $existingInstanceId: ${e.message}")
                    // Continue to reroute
                }
                log.warn("Bound instance $existingInstanceId is unavailable for session $sessionId, rerouting...")
            }

            val newInstanceId = try {
                sessionMappingService.rerouteSession(sessionId, placementExclusions(setOfNotNull(existingInstanceId)))
            } catch (e: IllegalStateException) {
                log.error("Failed to reroute session $sessionId: ${e.message}")
                throw e
            } catch (e: Exception) {
                log.error("Unexpected error during reroute for session $sessionId: ${e.message}", e)
                throw IllegalStateException("Reroute failed: ${e.message}", e)
            }

            val newInstance = instanceRegistry.getInstance(newInstanceId)
                ?: throw IllegalStateException("Rerouted instance $newInstanceId not found")
            log.info("Bound session $sessionId to instance $newInstanceId")
            sessionEvictor.requestEviction(sessionId, existingInstanceId)
            newInstance
        } catch (e: Exception) {
            log.error("Failed to resolve instance for session $sessionId: ${e.message}", e)
            throw e
        }
    }

    private fun resolveInstanceBlocking(sessionId: String): AgentInstance {
        return try {
            val existingInstanceId = sessionMappingService.getInstanceId(sessionId)

            if (existingInstanceId != null) {
                try {
                    val instance = instanceRegistry.getInstance(existingInstanceId)
                    if (instance != null &&
                        instance.isHealthy(heartbeatTimeoutMs) &&
                        !instance.isDraining()
                    ) {
                        return instance
                    }
                } catch (e: Exception) {
                    log.warn("Error checking instance $existingInstanceId: ${e.message}")
                    // Continue to reroute
                }
                log.warn("Bound instance $existingInstanceId is unavailable for stream session $sessionId, rerouting...")
            }

            val newInstanceId = try {
                sessionMappingService.rerouteSession(sessionId, placementExclusions(setOfNotNull(existingInstanceId)))
            } catch (e: IllegalStateException) {
                log.error("Failed to reroute session $sessionId: ${e.message}")
                throw e
            } catch (e: Exception) {
                log.error("Unexpected error during reroute for session $sessionId: ${e.message}", e)
                throw IllegalStateException("Reroute failed: ${e.message}", e)
            }

            val newInstance = instanceRegistry.getInstance(newInstanceId)
                ?: throw IllegalStateException("Rerouted instance $newInstanceId not found")
            sessionEvictor.requestEviction(sessionId, existingInstanceId)
            newInstance
        } catch (e: Exception) {
            log.error("Failed to resolve instance for session $sessionId: ${e.message}", e)
            throw e
        }
    }

    /**
     * Instances that must not receive this session's next placement: what the caller already saw
     * fail, plus every instance whose breaker is tripped.
     *
     * Placement is the only thing the breaker decides. A session already bound to an instance whose
     * breaker just tripped keeps that binding, because re-homing on a breaker reading re-bound
     * every session on the instance at once — turning one slow agent into a cluster-wide rebinding
     * storm — and the binding would come straight back on the next request anyway.
     */
    private fun placementExclusions(excluded: Set<String>): Set<String> {
        val candidates = instanceRegistry.getHealthyInstances().map { it.instanceId }
        if (candidates.isEmpty()) return excluded
        val tripped = circuitBreaker.trippedInstances(candidates)
        return if (tripped.isEmpty()) excluded else excluded + tripped
    }

    private suspend fun <T> executeWithRetry(
        sessionId: String,
        endpoint: String,
        action: suspend (AgentInstance) -> T,
    ): T {
        val instance = resolveInstance(sessionId)
        val oldInstanceId = MDC.get("instanceId")
        MDC.put("instanceId", instance.instanceId)
        trackPlacement(instance)
        try {
            val result = action(instance)
            circuitBreaker.recordSuccess(instance.instanceId)
            return result
        } catch (e: Exception) {
            val errorMsg = describeProxyError(e, endpoint, instance)
            log.error("Failed to proxy $endpoint for session $sessionId: $errorMsg", e)

            // 4xx errors are not retryable — the request itself is invalid, and an instance that
            // answered with 400 is not an instance whose request path is broken.
            if (!isRetryableError(e)) {
                log.warn("Non-retryable error for $endpoint, session $sessionId — skipping failover")
                throw e
            }

            circuitBreaker.recordFailure(instance.instanceId)
            // Clean up MDC before failover to avoid pollution
            restoreMdc("instanceId", oldInstanceId)
            return retryFailover(sessionId, endpoint, instance.instanceId, action)
        }
    }

    private suspend fun <T> retryFailover(
        sessionId: String,
        endpoint: String,
        failedInstanceId: String,
        action: suspend (AgentInstance) -> T,
    ): T {
        val excluded = mutableSetOf(failedInstanceId)
        var leftBehind = failedInstanceId
        var lastError: Exception? = null
        for (attempt in 1..failoverMaxRetries) {
            var currentInstance: AgentInstance? = null
            try {
                excluded += placementExclusions(excluded)
                val newInstanceId = sessionMappingService.rerouteSession(sessionId, excluded)
                excluded.add(newInstanceId)
                currentInstance = instanceRegistry.getInstance(newInstanceId)
                    ?: throw IllegalStateException("Failover instance $newInstanceId not found")

                if (!circuitBreaker.allowRequest(newInstanceId)) {
                    lastError = lastError ?: IllegalStateException("Circuit open for failover instance $newInstanceId")
                    log.warn("Failover instance $newInstanceId circuit is open, skipping")
                    continue
                }

                sessionEvictor.requestEviction(sessionId, leftBehind)
                leftBehind = newInstanceId

                MDC.put("instanceId", currentInstance.instanceId)
                trackPlacement(currentInstance)
                log.info("Failover attempt $attempt for $endpoint, session $sessionId -> ${currentInstance.instanceId}")
                failoverCounter(endpoint, attempt).increment()

                val result = action(currentInstance)
                circuitBreaker.recordSuccess(newInstanceId)
                return result
            } catch (e: Exception) {
                lastError = e
                // Clean up MDC after failed attempt
                currentInstance?.let {
                    if (isRetryableError(e)) circuitBreaker.recordFailure(it.instanceId)
                    MDC.remove("instanceId")
                }
                log.error("Failover attempt $attempt failed for $endpoint, session $sessionId: ${e.message}", e)
            }
        }
        throw lastError ?: IllegalStateException("Failover exhausted for $endpoint, session $sessionId")
    }

    private fun buildStreamFlux(
        sessionId: String,
        requestId: String,
        instance: AgentInstance,
        request: ChatAgentRequest,
        attempt: Int,
        excluded: Set<String>,
    ): Flux<ChatEvent> {
        // Assembled on the request thread, which is the only place a placement can be recorded for the
        // call log: the events themselves arrive on an agent's event loop.
        trackPlacement(instance)
        // Has anything already gone out to the client? Failover is only honest before the first
        // event: replaying a prompt that already produced text appends a second answer under the
        // first one, and runs whatever the agent already did to the session a second time.
        val delivered = AtomicBoolean(false)
        return agentServiceClient.chatStream(instance.getBaseUrl(), request, requestId)
            .doOnNext { event ->
                delivered.set(true)
                log.debug("[Router←Agent] Stream event received for session=$sessionId: ${event.javaClass.simpleName}")
            }
            .doOnComplete {
                log.info("[Router←Agent] Stream completed for session=$sessionId")
                circuitBreaker.recordSuccess(instance.instanceId)
                streamOkCounter.increment()
                sessionMappingService.refreshActiveTime(sessionId)
            }
            .doOnCancel {
                log.info("Stream cancelled by client for session $sessionId")
            }
            .onErrorResume { e ->
                if (isRetryableError(e)) {
                    circuitBreaker.recordFailure(instance.instanceId)
                }
                val errorMsg = describeProxyError(e, "stream", instance)
                log.error("Stream proxy error for session $sessionId on ${instance.instanceId}: $errorMsg", e)
                streamErrorCounter.increment()

                if (isConnectivityError(e) && attempt < failoverMaxRetries && !delivered.get()) {
                    return@onErrorResume tryStreamFailover(
                        sessionId,
                        requestId,
                        request,
                        attempt + 1,
                        excluded + instance.instanceId,
                        instance.instanceId,
                    )
                }

                if (isConnectivityError(e) && delivered.get()) {
                    log.warn("Stream for session $sessionId broke after $instance.instanceId started answering; the answer is not replayed elsewhere")
                } else if (isConnectivityError(e)) {
                    log.warn("Stream failover exhausted for session $sessionId after $attempt attempts")
                }

                Flux.just(
                    ErrorChatEvent(
                        code = HarnaxErrorCode.ROUTER_PROXY_ERROR.code,
                        message = errorMsg,
                    ),
                    EndEventChatEvent(),
                )
            }
    }

    private fun tryStreamFailover(
        sessionId: String,
        requestId: String,
        request: ChatAgentRequest,
        attempt: Int,
        excluded: Set<String>,
        leftBehind: String,
    ): Flux<ChatEvent> {
        return Flux.defer {
            try {
                val skip = placementExclusions(excluded)
                val newInstanceId = sessionMappingService.rerouteSession(sessionId, skip)
                val newInstance = instanceRegistry.getInstance(newInstanceId)

                if (newInstance == null || !circuitBreaker.allowRequest(newInstanceId)) {
                    return@defer Flux.just(
                        ErrorChatEvent(
                            code = HarnaxErrorCode.ROUTER_NO_INSTANCE.code,
                            message = "No available instance for failover",
                        ),
                        EndEventChatEvent(),
                    )
                }

                log.info("Stream failover attempt $attempt for session $sessionId -> ${newInstance.instanceId}")
                failoverCounter("stream", attempt).increment()
                sessionEvictor.requestEviction(sessionId, leftBehind)
                buildStreamFlux(sessionId, requestId, newInstance, request, attempt, skip)
            } catch (e: Exception) {
                log.error("Stream failover attempt $attempt failed for session $sessionId: ${e.message}", e)
                Flux.just(
                    ErrorChatEvent(
                        code = HarnaxErrorCode.ROUTER_PROXY_ERROR.code,
                        message = "Failover failed: ${e.message}",
                    ),
                    EndEventChatEvent(),
                )
            }
        }
    }

    private fun isConnectivityError(e: Throwable): Boolean {
        val connectivityExceptions = setOf(
            java.net.ConnectException::class.java,
            java.net.SocketTimeoutException::class.java,
            java.net.NoRouteToHostException::class.java,
            java.net.UnknownHostException::class.java,
            io.netty.channel.ConnectTimeoutException::class.java,
        )
        var current: Throwable? = e
        while (current != null) {
            if (connectivityExceptions.any { it.isInstance(current) }) return true
            current = current.cause
        }
        return false
    }

    /**
     * Determine if an error is retryable. 4xx HTTP errors are NOT retryable
     * (bad request, not found, auth failures); connectivity errors and 5xx are.
     */
    private fun isRetryableError(e: Throwable): Boolean {
        if (isConnectivityError(e)) return true
        var current: Throwable? = e
        while (current != null) {
            if (current is WebClientResponseException) {
                val status = current.statusCode.value()
                // Only retry on 5xx (server errors) or 429 (rate limited)
                return status >= 500 || status == 429
            }
            current = current.cause
        }
        // Non-HTTP, non-connectivity errors: don't retry (e.g., deserialization failures)
        return false
    }

    /**
     * Build a descriptive error message for proxy failures, with specific context for auth errors.
     */
    private fun describeProxyError(e: Throwable, endpoint: String, instance: AgentInstance): String {
        val baseUrl = instance.getBaseUrl()
        if (e is WebClientResponseException) {
            val status = e.statusCode.value()
            val body = e.responseBodyAsString.take(200)
            return when (status) {
                401 ->
                    "[Router→Agent] Authentication failed (401) calling $baseUrl/api/agent/$endpoint. " +
                        "JWT may be invalid or expired. Response: $body"
                403 ->
                    "[Router→Agent] Forbidden (403) calling $baseUrl/api/agent/$endpoint. " +
                        "Access denied — endpoint may be internal-only. Response: $body"
                else -> "[Router→Agent] HTTP $status calling $baseUrl/api/agent/$endpoint. Response: $body"
            }
        }
        return "[Router→Agent] Failed to proxy $endpoint to $baseUrl: ${e.message}"
    }

    private fun getOrGenerateRequestId(request: ChatAgentRequest): String = request.requestId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    private fun setMDC(sessionId: String, requestId: String?) {
        MDC.put("sessionId", sessionId)
        if (requestId != null) MDC.put("requestId", requestId)
    }

    /**
     * Leave a trace of the placement on the request itself. MDC carries it for the logs and dies with
     * the routing thread; the call log needs the value to outlive that thread, and only a thread with
     * a bound HTTP request can write it. A placement decided after the work left the request thread —
     * a stream failing over on an agent's event loop — simply goes unrecorded.
     */
    private fun trackPlacement(instance: AgentInstance) {
        val servletRequest = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request ?: return
        servletRequest.setAttribute(ROUTED_INSTANCE_ATTR, instance.instanceId)
    }

    private fun clearMDC() {
        mdcKeys.forEach { MDC.remove(it) }
    }

    /**
     * Restore MDC[key] to a previous value (null means remove).
     * Used to restore an instanceId set by an outer scope when the proxy fails, avoiding log cross-contamination.
     */
    private fun restoreMdc(key: String, value: String?) {
        if (value != null) MDC.put(key, value) else MDC.remove(key)
    }
}
