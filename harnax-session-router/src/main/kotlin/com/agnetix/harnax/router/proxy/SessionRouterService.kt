package com.agnetix.harnax.router.proxy

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.IdempotencyService
import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class SessionRouterService(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
    private val idempotencyService: IdempotencyService,
    private val circuitBreaker: InstanceCircuitBreaker,
    private val webClient: WebClient,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val tokenProvider: InternalTokenProvider,
    private val meterRegistry: MeterRegistry,
    @Value($$"${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
    @Value($$"${router.proxy.stream-timeout-minutes:10}")
    private val streamTimeoutMinutes: Long,
    @Value($$"${router.proxy.failover-max-retries:2}")
    private val failoverMaxRetries: Int,
) {

    private val log = LoggerFactory.getLogger(SessionRouterService::class.java)

    private val mdcKeys = setOf("sessionId", "requestId", "instanceId")

    // TODO [P0] buildCurl() includes JWT auth headers and request body in INFO-level log output.
    //   This leaks credentials and user data to production log aggregators.
    //   Fix: remove auth headers from log output, or mask them; redact sensitive body fields.
    private fun buildCurl(url: String, body: String): String {
        val headers = tokenProvider.authHeaders()
        val headerArgs = headers.entries.joinToString(" ") { (k, v) -> "-H '$k: $v'" }
        return "curl -X POST '$url' -H 'Content-Type: application/json' $headerArgs -d '$body'"
    }

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
                val url = "${targetInstance.getBaseUrl()}/api/agent/chat"
                val json = objectMapper.writeValueAsString(request)
                log.info("[Router→Agent] {}", buildCurl(url, json))
                val startTime = System.currentTimeMillis()
                val agentResult = withContext(Dispatchers.IO) {
                    restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(object : ParameterizedTypeReference<ResultVo<ChatResponse>>() {})
                } ?: ResultVo.error("No response from agent-service")
                val elapsed = System.currentTimeMillis() - startTime
                log.info("[Router←Agent] Received agent response for session=$sessionId, code=${agentResult.code}, success=${agentResult.isSuccess()}, contentLength=${agentResult.data?.content?.length ?: 0}, elapsed=${elapsed}ms")
                agentResult
            }

            sample.stop(chatTimer)
            if (result.isSuccess()) chatOkCounter.increment() else chatErrorCounter.increment()
            log.info("[Router] proxyChatRequest final result for session=$sessionId, code=${result.code}, success=${result.isSuccess()}, contentLength=${result.data?.content?.length ?: 0}")
            sessionMappingService.refreshActiveTime(sessionId)
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

            return buildStreamFlux(sessionId, requestId, instance, request)
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
                val url = "${targetInstance.getBaseUrl()}/api/agent/command"
                val json = objectMapper.writeValueAsString(request)
                log.info("[Router→Agent] {}", buildCurl(url, json))
                withContext(Dispatchers.IO) {
                    restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})
                } ?: ResultVo.error("No response from agent-service")
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
            val url = "${instance.getBaseUrl()}/api/agent/confirm"

            return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(ChatEvent::class.java)
                .limitRate(10)
                .timeout(Duration.ofMinutes(streamTimeoutMinutes))
                .doOnCancel {
                    log.info("Confirm stream cancelled by client for session=$sessionId")
                }
                .onErrorResume { e ->
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
                val url = "${targetInstance.getBaseUrl()}/api/agent/session/$sessionId"
                webClient.delete()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(object : ParameterizedTypeReference<ResultVo<String>>() {})
                    .awaitSingleOrNull()
                    ?: ResultVo.error("No response from agent-service")
            }
        } finally {
            clearMDC()
        }
    }

    suspend fun proxyLoadHistory(sessionId: String): ResultVo<List<Any>> {
        setMDC(sessionId, null)
        try {
            return executeWithRetry(sessionId, "loadHistory") { targetInstance ->
                val url = "${targetInstance.getBaseUrl()}/api/agent/chat/history/$sessionId"
                webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(object : ParameterizedTypeReference<ResultVo<List<Any>>>() {})
                    .awaitSingleOrNull()
                    ?: ResultVo.error("No response from agent-service")
            }
        } finally {
            clearMDC()
        }
    }

    suspend fun proxyLoadPlans(sessionId: String): ResultVo<List<Any>> {
        setMDC(sessionId, null)
        try {
            return executeWithRetry(sessionId, "loadPlans") { targetInstance ->
                val url = "${targetInstance.getBaseUrl()}/api/agent/session/$sessionId/plans"
                webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(object : ParameterizedTypeReference<ResultVo<List<Any>>>() {})
                    .awaitSingleOrNull()
                    ?: ResultVo.error("No response from agent-service")
            }
        } finally {
            clearMDC()
        }
    }

    suspend fun proxyLoadCurrentPlan(sessionId: String): ResultVo<Any?> {
        setMDC(sessionId, null)
        try {
            return executeWithRetry(sessionId, "loadCurrentPlan") { targetInstance ->
                val url = "${targetInstance.getBaseUrl()}/api/agent/session/$sessionId/current-plan"
                webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(object : ParameterizedTypeReference<ResultVo<Any?>>() {})
                    .awaitSingleOrNull()
                    ?: ResultVo.error("No response from agent-service")
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
    ): Flux<ChatEvent> {
        val url = "${instance.getBaseUrl()}/api/agent/chat/stream"

        return webClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-Id", requestId)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(ChatEvent::class.java)
            .limitRate(10)
            .timeout(Duration.ofMinutes(streamTimeoutMinutes))
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

                if (isConnectivityError(e)) {
                    return@onErrorResume tryStreamFailover(sessionId, requestId, request)
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

                log.info("Stream failover for session $sessionId -> ${newInstance.instanceId}")
                failoverCounter("stream", 1).increment()
                buildStreamFlux(sessionId, requestId, newInstance, request)
            } catch (e: Exception) {
                log.error("Stream failover failed for session $sessionId: ${e.message}", e)
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

    private fun isConnectivityError(e: Throwable): Boolean = e is java.net.ConnectException ||
        e is java.net.SocketTimeoutException ||
        e is java.net.NoRouteToHostException ||
        e is java.net.UnknownHostException ||
        e is io.netty.channel.ConnectTimeoutException ||
        e.cause is java.net.ConnectException

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
