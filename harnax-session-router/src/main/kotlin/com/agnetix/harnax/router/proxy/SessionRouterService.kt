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
import com.agnetix.harnax.router.service.IdempotencyService
import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.UUID

@Service
class SessionRouterService(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
    private val idempotencyService: IdempotencyService,
    private val circuitBreaker: InstanceCircuitBreaker,
    private val webClient: WebClient,
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

    suspend fun proxyChatRequest(request: ChatAgentRequest): ResultVo<ChatResponse> {
        val sessionId = request.sessionId
        val requestId = getOrGenerateRequestId(request)
        setMDC(sessionId, requestId)
        try {
            if (!idempotencyService.tryAcquire(requestId)) {
                log.warn("Duplicate request detected: $requestId")
                return ResultVo.error("Duplicate request: $requestId")
            }

            val instance = resolveInstance(sessionId)
            MDC.put("instanceId", instance.instanceId)
            val timer = meterRegistry.timer("router.proxy.duration", "endpoint", "chat")
            val sample = io.micrometer.core.instrument.Timer.start()

            val result = executeWithRetry(sessionId, "chat") { targetInstance ->
                val url = "${targetInstance.getBaseUrl()}/api/agent/chat"
                webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", requestId)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(object : ParameterizedTypeReference<ResultVo<ChatResponse>>() {})
                    .awaitSingleOrNull()
                    ?: ResultVo.error("No response from agent-service")
            }

            sample.stop(timer)
            meterRegistry.counter(
                "router.proxy.requests",
                "endpoint",
                "chat",
                "status",
                if (result.isSuccess()) "ok" else "error",
            ).increment()
            sessionMappingService.refreshActiveTime(sessionId)
            return result
        } finally {
            clearMDC()
        }
    }

    fun proxyStreamRequest(request: ChatAgentRequest): Flux<ChatEvent> {
        val sessionId = request.sessionId
        val requestId = getOrGenerateRequestId(request)
        setMDC(sessionId, requestId)
        try {
            val instance = resolveInstanceBlocking(sessionId)
            MDC.put("instanceId", instance.instanceId)

            return buildStreamFlux(sessionId, requestId, instance, request)
                .doFinally { clearMDC() }
        } catch (e: Exception) {
            clearMDC()
            log.error("Failed to resolve instance for stream session $sessionId: ${e.message}", e)
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
                webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})
                    .awaitSingleOrNull()
                    ?: ResultVo.error("No response from agent-service")
            }.also {
                sessionMappingService.refreshActiveTime(sessionId)
            }
        } finally {
            clearMDC()
        }
    }

    fun proxyConfirmStreamRequest(request: ConfirmAgentRequest): Flux<ChatEvent> {
        val sessionId = request.sessionId
        setMDC(sessionId, null)
        try {
            val instance = resolveInstanceBlocking(sessionId)
            MDC.put("instanceId", instance.instanceId)
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
                    log.info("Confirm stream cancelled by client for session $sessionId")
                }
                .doFinally { clearMDC() }
                .onErrorResume { e ->
                    log.error("Confirm stream proxy error for session $sessionId: ${e.message}", e)
                    Flux.just(
                        ErrorChatEvent(
                            code = HarnaxErrorCode.ROUTER_PROXY_ERROR.code,
                            message = e.message ?: "Failed to reach agent-service",
                        ),
                        EndEventChatEvent(),
                    )
                }
        } catch (e: Exception) {
            clearMDC()
            log.error("Failed to resolve instance for confirm stream session $sessionId: ${e.message}", e)
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
                    .bodyToMono(object : ParameterizedTypeReference<ResultVo<Any>>() {})
                    .awaitSingleOrNull()
                    ?: ResultVo.error("No response from agent-service")
            }
        } finally {
            clearMDC()
        }
    }

    private suspend fun resolveInstance(sessionId: String): AgentInstance {
        val existingInstanceId = sessionMappingService.getInstanceId(sessionId)

        if (existingInstanceId != null) {
            val instance = instanceRegistry.getInstance(existingInstanceId)
            if (instance != null && instance.isHealthy(heartbeatTimeoutMs) && !instance.isDraining() &&
                !circuitBreaker.isOpen(existingInstanceId)
            ) {
                return instance
            }
            log.warn("Bound instance $existingInstanceId is unavailable for session $sessionId, rerouting...")
        }

        val newInstanceId = sessionMappingService.rerouteSession(sessionId)
        val newInstance = instanceRegistry.getInstance(newInstanceId)
            ?: throw IllegalStateException("Rerouted instance $newInstanceId not found")
        log.info("Bound session $sessionId to instance $newInstanceId")
        return newInstance
    }

    private fun resolveInstanceBlocking(sessionId: String): AgentInstance {
        val existingInstanceId = sessionMappingService.getInstanceId(sessionId)

        if (existingInstanceId != null) {
            val instance = instanceRegistry.getInstance(existingInstanceId)
            if (instance != null && instance.isHealthy(heartbeatTimeoutMs) && !instance.isDraining() &&
                !circuitBreaker.isOpen(existingInstanceId)
            ) {
                return instance
            }
        }

        val newInstanceId = sessionMappingService.rerouteSession(sessionId)
        return instanceRegistry.getInstance(newInstanceId)
            ?: throw IllegalStateException("Rerouted instance $newInstanceId not found")
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
            log.error("Failed to proxy $endpoint for session $sessionId: ${e.message}", e)
            // Clean up MDC before failover to avoid pollution
            if (oldInstanceId != null) {
                MDC.put("instanceId", oldInstanceId)
            } else {
                MDC.remove("instanceId")
            }
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
                meterRegistry.counter("router.failover.count", "endpoint", endpoint, "attempt", "$attempt").increment()

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
            .doOnNext { circuitBreaker.recordSuccess(instance.instanceId) }
            .doOnComplete {
                meterRegistry.counter("router.proxy.requests", "endpoint", "stream", "status", "ok").increment()
                sessionMappingService.refreshActiveTime(sessionId)
            }
            .doOnCancel {
                log.info("Stream cancelled by client for session $sessionId")
            }
            .onErrorResume { e ->
                circuitBreaker.recordFailure(instance.instanceId)
                log.error("Stream proxy error for session $sessionId on ${instance.instanceId}: ${e.message}", e)
                meterRegistry.counter("router.proxy.requests", "endpoint", "stream", "status", "error").increment()

                if (isConnectivityError(e)) {
                    return@onErrorResume tryStreamFailover(sessionId, requestId, request)
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
                meterRegistry.counter("router.failover.count", "endpoint", "stream", "attempt", "1").increment()
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
        e is java.net.UnknownHostException ||
        e is io.netty.channel.ConnectTimeoutException ||
        e.cause is java.net.ConnectException

    private fun getOrGenerateRequestId(request: ChatAgentRequest): String = request.requestId.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()

    private fun setMDC(sessionId: String, requestId: String?) {
        MDC.put("sessionId", sessionId)
        if (requestId != null) MDC.put("requestId", requestId)
    }

    private fun clearMDC() {
        mdcKeys.forEach { MDC.remove(it) }
    }
}
