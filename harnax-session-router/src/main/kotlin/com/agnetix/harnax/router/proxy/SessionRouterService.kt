package com.agnetix.harnax.router.proxy

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux

/**
 * Core session router service.
 * Proxies requests from channel-service to the correct agent-service instance,
 * handling session binding and failover.
 */
@Service
class SessionRouterService(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
    private val webClient: WebClient,
    @Value($$"${router.proxy.connect-timeout-ms:5000}")
    private val connectTimeoutMs: Long,
    @Value($$"${router.proxy.read-timeout-ms:60000}")
    private val readTimeoutMs: Long,
    @Value($$"${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
) {

    private val log = LoggerFactory.getLogger(SessionRouterService::class.java)

    /**
     * Proxy a direct (non-streaming) chat request to the correct agent-service instance.
     * If the session has no binding, select a healthy instance and bind.
     * If the bound instance is down, reroute to another instance.
     */
    suspend fun proxyChatRequest(request: ChatAgentRequest): ResultVo<ChatResponse> {
        val sessionId = request.sessionId
        val instance = resolveInstance(sessionId)
        val url = "${instance.getBaseUrl()}/api/agent/chat"

        log.debug("Proxying chat request for session $sessionId to instance ${instance.instanceId} at $url")

        try {
            val response = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<ResultVo<ChatResponse>>() {})
                .awaitSingleOrNull()

            sessionMappingService.refreshActiveTime(sessionId)

            return response ?: ResultVo.error("No response from agent-service")
        } catch (e: Exception) {
            log.error("Failed to proxy chat request for session $sessionId: ${e.message}", e)
            return tryFailover(sessionId, request)
        }
    }

    /**
     * Proxy an SSE streaming request to the correct agent-service instance.
     * Returns a Flux<ChatEvent> that can be streamed back to the caller.
     */
    fun proxyStreamRequest(request: ChatAgentRequest): Flux<ChatEvent> {
        val sessionId = request.sessionId
        val instance = resolveInstanceBlocking(sessionId)
        val url = "${instance.getBaseUrl()}/api/agent/chat/stream"

        log.debug("Proxying stream request for session $sessionId to instance ${instance.instanceId} at $url")

        return webClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(ChatEvent::class.java)
            .doOnComplete {
                sessionMappingService.refreshActiveTime(sessionId)
            }
            .onErrorResume { e ->
                log.error("Stream proxy error for session $sessionId: ${e.message}", e)
                Flux.just(
                    ErrorChatEvent(
                        code = HarnaxErrorCode.ROUTER_PROXY_ERROR.code,
                        message = e.message ?: "Failed to reach agent-service",
                    ),
                    EndEventChatEvent(),
                )
            }
    }

    /**
     * Proxy a command request to the correct agent-service instance.
     */
    suspend fun proxyCommandRequest(request: CommandAgentRequest): ResultVo<CommandResponse> {
        val sessionId = request.sessionId
        val instance = resolveInstance(sessionId)
        val url = "${instance.getBaseUrl()}/api/agent/command"

        log.debug("Proxying command request for session $sessionId to instance ${instance.instanceId} at $url")

        try {
            val response = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})
                .awaitSingleOrNull()

            sessionMappingService.refreshActiveTime(sessionId)

            return response ?: ResultVo.error("No response from agent-service")
        } catch (e: Exception) {
            log.error("Failed to proxy command request for session $sessionId: ${e.message}", e)
            return tryCommandFailover(sessionId, request)
        }
    }

    /**
     * Resolve which agent-service instance should handle this session.
     * Uses existing binding if available and healthy, otherwise creates new binding.
     */
    private suspend fun resolveInstance(sessionId: String): AgentInstance {
        val existingInstanceId = sessionMappingService.getInstanceId(sessionId)

        if (existingInstanceId != null) {
            val instance = instanceRegistry.getInstance(existingInstanceId)
            if (instance != null && instance.isHealthy(heartbeatTimeoutMs)) {
                return instance
            }
            // Instance is down, need to reroute
            log.warn("Bound instance $existingInstanceId is unhealthy for session $sessionId, rerouting...")
        }

        // No binding or unhealthy binding - select a new instance
        val healthyInstances = instanceRegistry.getHealthyInstances()
        if (healthyInstances.isEmpty()) {
            throw IllegalStateException("No healthy agent-service instances available for session $sessionId")
        }

        val newInstance = selectLeastLoadedInstance(healthyInstances)
        sessionMappingService.bindSession(sessionId, newInstance.instanceId)
        log.info("Bound session $sessionId to instance ${newInstance.instanceId}")
        return newInstance
    }

    /**
     * Blocking version of resolveInstance for stream proxy.
     */
    private fun resolveInstanceBlocking(sessionId: String): AgentInstance {
        val existingInstanceId = sessionMappingService.getInstanceId(sessionId)

        if (existingInstanceId != null) {
            val instance = instanceRegistry.getInstance(existingInstanceId)
            if (instance != null && instance.isHealthy(heartbeatTimeoutMs)) {
                return instance
            }
        }

        val healthyInstances = instanceRegistry.getHealthyInstances()
        if (healthyInstances.isEmpty()) {
            throw IllegalStateException("No healthy agent-service instances available")
        }

        val newInstance = selectLeastLoadedInstance(healthyInstances)
        sessionMappingService.bindSession(sessionId, newInstance.instanceId)
        return newInstance
    }

    /**
     * Try failover by rerouting to another instance.
     */
    private suspend fun tryFailover(
        sessionId: String,
        request: ChatAgentRequest,
    ): ResultVo<ChatResponse> {
        try {
            val newInstance = sessionMappingService.rerouteSession(sessionId)
            val url = "${instanceRegistry.getInstance(newInstance)?.getBaseUrl()}/api/agent/chat"

            val response = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<ResultVo<ChatResponse>>() {})
                .awaitSingleOrNull()

            return response ?: ResultVo.error("No response from failover instance")
        } catch (failoverError: Exception) {
            log.error("Failover also failed for session $sessionId: ${failoverError.message}", failoverError)
            return ResultVo.error("Failover failed: ${failoverError.message}")
        }
    }

    /**
     * Try failover for command requests by rerouting to another instance.
     */
    private suspend fun tryCommandFailover(
        sessionId: String,
        request: CommandAgentRequest,
    ): ResultVo<CommandResponse> {
        try {
            val newInstance = sessionMappingService.rerouteSession(sessionId)
            val url = "${instanceRegistry.getInstance(newInstance)?.getBaseUrl()}/api/agent/command"

            val response = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})
                .awaitSingleOrNull()

            return response ?: ResultVo.error("No response from failover instance")
        } catch (failoverError: Exception) {
            log.error("Command failover also failed for session $sessionId: ${failoverError.message}", failoverError)
            return ResultVo.error("Command failover failed: ${failoverError.message}")
        }
    }

    /**
     * Select the instance with the least number of bound sessions.
     */
    private fun selectLeastLoadedInstance(instances: List<AgentInstance>): AgentInstance = instances.minByOrNull {
        // Simple heuristic: use round-robin based on instance ID hash
        it.instanceId.hashCode()
    } ?: instances.first()
}
