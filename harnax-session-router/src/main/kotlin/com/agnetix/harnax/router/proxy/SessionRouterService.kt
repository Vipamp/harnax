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
import com.agnetix.harnax.router.service.SessionMappingService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class SessionRouterService(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
    private val idempotencyService: IdempotencyService,
    private val circuitBreaker: InstanceCircuitBreaker,
    private val agentServiceClient: AgentServiceClient,
    private val meterRegistry: MeterRegistry,
    @Value($$"${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
    @Value($$"${router.proxy.failover-max-retries:2}")
    private val failoverMaxRetries: Int,
) {

    private val log = LoggerFactory.getLogger(SessionRouterService::class.java)

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
        val requestId = getOrGenerateRequestId(request)
        setMDC(sessionId, requestId)
        try {
            if (!idempotencyService.tryAcquire(requestId)) {
                log.warn("Duplicate request detected: $requestId")
                return ResultVo.error("Duplicate request: $requestId")
            }

            val sample = Timer.start()

            val result = executeWithRetry(sessionId, "chat") { targetInstance ->
                agentServiceClient.chat(targetInstance.getBaseUrl(), request)
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
            clearMDC()
        }
    }

    fun proxyStreamRequest(request: ChatAgentRequest): Flux<ChatEvent> {
        val sessionId = request.sessionId
        val requestId = getOrGenerateRequestId(request)
        // Note: MDC is not set here because the returned Flux is subscribed to and executed
        // on a Netty event loop thread, where the servlet thread's MDC is not visible.
        // Context (sessionId, requestId) is logged directly in each operator's messages.
        try {
            val instance = resolveInstanceBlocking(sessionId)
            log.info("Stream proxy for session=$sessionId, requestId=$requestId -> instance=${instance.instanceId}")

            return buildStreamFlux(sessionId, requestId, instance, request, attempt = 0)
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
        // Note: MDC is not set here for the same thread-safety reasons as proxyStreamRequest.
        try {
            val instance = resolveInstanceBlocking(sessionId)
            log.info("Confirm stream proxy for session=$sessionId -> instance=${instance.instanceId}")

            return agentServiceClient.confirmStream(instance.getBaseUrl(), request)
                .doOnComplete {
                    circuitBreaker.recordSuccess(instance.instanceId)
                    streamOkCounter.increment()
                    sessionMappingService.refreshActiveTime(sessionId)
                }
                .doOnCancel {
                    log.info("Confirm stream cancelled by client for session=$sessionId")
                }
                .onErrorResume { e ->
                    circuitBreaker.recordFailure(instance.instanceId)
                    streamErrorCounter.increment()
                    log.error("Confirm stream proxy error for session=$sessionId: ${e.message}", e)
                    Flux.just(
                        ErrorChatEvent(
                            code = HarnaxErrorCode.ROUTER_PROXY_ERROR.code,
                            message = e.message ?: "Failed to reach agent-service",
                        ),
                        EndEventChatEvent(),
                    )
                }
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

    suspend fun proxyClearSession(sessionId: String): ResultVo<String> {
        setMDC(sessionId, null)
        try {
            return executeWithRetry(sessionId, "clearSession") { targetInstance ->
                agentServiceClient.clearSession(targetInstance.getBaseUrl(), sessionId)
            }
        } finally {
            clearMDC()
        }
    }

    suspend fun proxyLoadHistory(sessionId: String): ResultVo<List<Any>> {
        setMDC(sessionId, null)
        try {
            return executeWithRetry(sessionId, "loadHistory") { targetInstance ->
                agentServiceClient.loadHistory(targetInstance.getBaseUrl(), sessionId)
            }
        } finally {
            clearMDC()
        }
    }

    suspend fun proxyLoadPlans(sessionId: String): ResultVo<List<Any>> {
        setMDC(sessionId, null)
        try {
            return executeWithRetry(sessionId, "loadPlans") { targetInstance ->
                agentServiceClient.loadPlans(targetInstance.getBaseUrl(), sessionId)
            }
        } finally {
            clearMDC()
        }
    }

    suspend fun proxyLoadCurrentPlan(sessionId: String): ResultVo<Any?> {
        setMDC(sessionId, null)
        try {
            return executeWithRetry(sessionId, "loadCurrentPlan") { targetInstance ->
                agentServiceClient.loadCurrentPlan(targetInstance.getBaseUrl(), sessionId)
            }
        } finally {
            clearMDC()
        }
    }

    // ---- Workspace proxy methods ----

    suspend fun proxyWorkspaceListFiles(sessionId: String, path: String): ResultVo<List<Map<String, Any>>> {
        setMDC(sessionId, null)
        try {
            return executeWithRetry(sessionId, "workspaceFiles") { targetInstance ->
                agentServiceClient.workspaceListFiles(targetInstance.getBaseUrl(), sessionId, path)
            }
        } finally {
            clearMDC()
        }
    }

    suspend fun proxyWorkspaceReadFile(sessionId: String, path: String): ResultVo<Map<String, Any>> {
        setMDC(sessionId, null)
        try {
            return executeWithRetry(sessionId, "workspaceRead") { targetInstance ->
                agentServiceClient.workspaceReadFile(targetInstance.getBaseUrl(), sessionId, path)
            }
        } finally {
            clearMDC()
        }
    }

    suspend fun proxyWorkspaceStatus(sessionIds: String): ResultVo<Map<String, Map<String, Any>>> {
        log.info("[proxyWorkspaceStatus] Starting for sessions: $sessionIds")
        // Use the first sessionId for routing; all sessions should be on the same agent-service
        val firstId = sessionIds.split(",").firstOrNull()?.trim() ?: return ResultVo.success(emptyMap())
        log.info("[proxyWorkspaceStatus] Using firstId for routing: $firstId")
        setMDC(firstId, null)
        try {
            return executeWithRetry(firstId, "workspaceStatus") { targetInstance ->
                agentServiceClient.workspaceStatus(targetInstance.getBaseUrl(), sessionIds)
            }
        } catch (e: Exception) {
            log.error("[proxyWorkspaceStatus] Exception: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        } finally {
            clearMDC()
        }
    }

    /**
     * Proxy file upload request to agent-service.
     */
    suspend fun proxyWorkspaceUpload(
        sessionId: String,
        path: String,
        fileName: String,
        fileBytes: ByteArray,
    ): ResultVo<Map<String, Any>> {
        setMDC(sessionId, null)
        try {
            return executeWithRetry(sessionId, "workspaceUpload") { targetInstance ->
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
            return executeWithRetry(sessionId, "workspaceDownload") { targetInstance ->
                agentServiceClient.workspaceDownload(targetInstance.getBaseUrl(), sessionId, path)
            }
        } finally {
            clearMDC()
        }
    }

    private suspend fun resolveInstance(sessionId: String): AgentInstance {
        return try {
            val existingInstanceId = sessionMappingService.getInstanceId(sessionId)

            if (existingInstanceId != null) {
                try {
                    val instance = instanceRegistry.getInstance(existingInstanceId)
                    if (instance != null &&
                        instance.isHealthy(heartbeatTimeoutMs) &&
                        !instance.isDraining() &&
                        !circuitBreaker.isOpen(existingInstanceId)
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
                sessionMappingService.rerouteSession(sessionId)
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
                        !instance.isDraining() &&
                        !circuitBreaker.isOpen(existingInstanceId)
                    ) {
                        return instance
                    }
                } catch (e: Exception) {
                    log.warn("Error checking instance $existingInstanceId: ${e.message}")
                    // Continue to reroute
                }
            }

            val newInstanceId = try {
                sessionMappingService.rerouteSession(sessionId)
            } catch (e: IllegalStateException) {
                log.error("Failed to reroute session $sessionId: ${e.message}")
                throw e
            } catch (e: Exception) {
                log.error("Unexpected error during reroute for session $sessionId: ${e.message}", e)
                throw IllegalStateException("Reroute failed: ${e.message}", e)
            }

            instanceRegistry.getInstance(newInstanceId)
                ?: throw IllegalStateException("Rerouted instance $newInstanceId not found")
        } catch (e: Exception) {
            log.error("Failed to resolve instance for session $sessionId: ${e.message}", e)
            throw e
        }
    }

    private suspend fun <T> executeWithRetry(
        sessionId: String,
        endpoint: String,
        action: suspend (AgentInstance) -> T,
    ): T {
        val instance = resolveInstance(sessionId)
        val oldInstanceId = MDC.get("instanceId")
        MDC.put("instanceId", instance.instanceId)
        try {
            val result = action(instance)
            circuitBreaker.recordSuccess(instance.instanceId)
            return result
        } catch (e: Exception) {
            circuitBreaker.recordFailure(instance.instanceId)
            val errorMsg = describeProxyError(e, endpoint, instance)
            log.error("Failed to proxy $endpoint for session $sessionId: $errorMsg", e)
            // Clean up MDC before failover to avoid pollution
            restoreMdc("instanceId", oldInstanceId)
            return retryFailover(sessionId, endpoint, action)
        }
    }

    private suspend fun <T> retryFailover(
        sessionId: String,
        endpoint: String,
        action: suspend (AgentInstance) -> T,
    ): T {
        var lastError: Exception? = null
        for (attempt in 1..failoverMaxRetries) {
            var currentInstance: AgentInstance? = null
            try {
                val newInstance = sessionMappingService.rerouteSession(sessionId)
                currentInstance = instanceRegistry.getInstance(newInstance)
                    ?: throw IllegalStateException("Failover instance $newInstance not found")

                if (circuitBreaker.isOpen(currentInstance.instanceId)) {
                    log.warn("Failover instance ${currentInstance.instanceId} circuit is open, skipping")
                    continue
                }

                MDC.put("instanceId", currentInstance.instanceId)
                log.info("Failover attempt $attempt for $endpoint, session $sessionId -> ${currentInstance.instanceId}")
                failoverCounter(endpoint, attempt).increment()

                val result = action(currentInstance)
                circuitBreaker.recordSuccess(currentInstance.instanceId)
                return result
            } catch (e: Exception) {
                lastError = e
                // Clean up MDC after failed attempt
                currentInstance?.let { MDC.remove("instanceId") }
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
    ): Flux<ChatEvent> {
        return agentServiceClient.chatStream(instance.getBaseUrl(), request, requestId)
            .doOnNext { event ->
                log.info("[Router←Agent] Stream event received for session=$sessionId: ${event.javaClass.simpleName}")
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
                circuitBreaker.recordFailure(instance.instanceId)
                val errorMsg = describeProxyError(e, "stream", instance)
                log.error("Stream proxy error for session $sessionId on ${instance.instanceId}: $errorMsg", e)
                streamErrorCounter.increment()

                if (isConnectivityError(e) && attempt < failoverMaxRetries) {
                    return@onErrorResume tryStreamFailover(sessionId, requestId, request, attempt + 1)
                }

                if (isConnectivityError(e) && attempt >= failoverMaxRetries) {
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
    ): Flux<ChatEvent> {
        return Flux.defer {
            try {
                val newInstanceId = sessionMappingService.rerouteSession(sessionId)
                val newInstance = instanceRegistry.getInstance(newInstanceId)

                if (newInstance == null || circuitBreaker.isOpen(newInstance.instanceId)) {
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
                buildStreamFlux(sessionId, requestId, newInstance, request, attempt)
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
