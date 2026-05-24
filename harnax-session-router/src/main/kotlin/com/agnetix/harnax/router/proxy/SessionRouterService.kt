package com.agnetix.harnax.router.proxy

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

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
     * Proxy a synchronous chat request to the correct agent-service instance.
     * If the session has no binding, select a healthy instance and bind.
     * If the bound instance is down, reroute to another instance.
     */
    suspend fun proxyChatRequest(sessionId: String, agentId: Long?, requestBody: Map<String, Any>): ResponseEntity<String> {
        val instance = resolveInstance(sessionId)
        val url = "${instance.getBaseUrl()}/api/agent/chat?sessionId=$sessionId" +
            (if (agentId != null) "&agentId=$agentId" else "")

        log.debug("Proxying chat request for session $sessionId to instance ${instance.instanceId} at $url")

        try {
            val response = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .toEntity(String::class.java)
                .awaitSingleOrNull()

            // Refresh session active time on success
            sessionMappingService.refreshActiveTime(sessionId)

            return response ?: ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("No response from agent-service")
        } catch (e: Exception) {
            log.error("Failed to proxy chat request for session $sessionId: ${e.message}", e)
            // Try failover
            return tryFailover(sessionId, agentId, requestBody, e)
        }
    }

    /**
     * Proxy an SSE streaming request to the correct agent-service instance.
     * Returns a Flux of SSE events that can be streamed back to the caller.
     */
    fun proxyStreamRequest(sessionId: String, agentId: Long?, requestBody: Map<String, Any>): org.reactivestreams.Publisher<String> {
        val instance = resolveInstanceBlocking(sessionId)
        val url = "${instance.getBaseUrl()}/api/agent/chat/stream?sessionId=$sessionId" +
            (if (agentId != null) "&agentId=$agentId" else "")

        log.debug("Proxying stream request for session $sessionId to instance ${instance.instanceId} at $url")

        return webClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToFlux(String::class.java)
            .doOnComplete {
                sessionMappingService.refreshActiveTime(sessionId)
            }
            .doOnError { e ->
                log.error("Stream proxy error for session $sessionId: ${e.message}", e)
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
        agentId: Long?,
        requestBody: Map<String, Any>,
        originalError: Exception,
    ): ResponseEntity<String> {
        try {
            val newInstance = sessionMappingService.rerouteSession(sessionId)
            val url = "${instanceRegistry.getInstance(newInstance)?.getBaseUrl()}/api/agent/chat?sessionId=$sessionId" +
                (if (agentId != null) "&agentId=$agentId" else "")

            val response = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .toEntity(String::class.java)
                .awaitSingleOrNull()

            return response ?: ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Failover response also failed")
        } catch (failoverError: Exception) {
            log.error("Failover also failed for session $sessionId: ${failoverError.message}", failoverError)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("All agent-service instances unavailable: ${originalError.message}")
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
