package com.agnetix.harnax.agent.service.registrar

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

/**
 * Agent Service Registrar.
 * Handles registration and heartbeat with the session-router service.
 * On startup, registers this instance with the router.
 * Periodically sends heartbeat to maintain the registration.
 */
@Component
class AgentServiceRegistrar(
    @Value($$"${agent.service.instance-id}") private val instanceId: String,
    @Value($$"${server.port:8082}") private val port: Int,
    @Value($$"${router.service.url}") private val routerUrl: String,
    @Value($$"${agent.service.heartbeat-interval-ms:10000}") private val heartbeatIntervalMs: Long,
) {

    private val log = LoggerFactory.getLogger(AgentServiceRegistrar::class.java)
    private val restTemplate = RestTemplate()
    private var registered = false

    /**
     * Register this agent-service instance with the session-router on startup.
     */
    @PostConstruct
    fun registerOnStartup() {
        try {
            val host = getLocalHost()
            log.info("Registering agent-service instance: $instanceId at $host:$port with router at $routerUrl")

            restTemplate.postForObject(
                "$routerUrl/api/router/instance/register?instanceId=$instanceId&host=$host&port=$port",
                null,
                Map::class.java,
            )
            registered = true
            log.info("Successfully registered with session-router")
        } catch (e: Exception) {
            log.warn("Failed to register with session-router (will retry): ${e.message}")
        }
    }

    /**
     * Send periodic heartbeat to the session-router.
     * Runs every heartbeatIntervalMs milliseconds.
     */
    @Scheduled(fixedRateString = "\${agent.service.heartbeat-interval-ms:10000}")
    fun sendHeartbeat() {
        if (!registered) {
            // Try to register again if initial registration failed
            registerOnStartup()
            return
        }

        try {
            restTemplate.postForObject(
                "$routerUrl/api/router/instance/heartbeat?instanceId=$instanceId",
                null,
                Map::class.java,
            )
            log.debug("Heartbeat sent successfully for instance: $instanceId")
        } catch (e: Exception) {
            log.warn("Heartbeat failed for instance $instanceId: ${e.message}")
        }
    }

    /**
     * Get the local host address.
     * In Docker/Kubernetes environments, this should be the service name or IP.
     */
    private fun getLocalHost(): String {
        // In production, this could be configured via environment variable
        return System.getenv("HOST_IP") ?: System.getenv("HOSTNAME") ?: "localhost"
    }
}
