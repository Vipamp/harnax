package com.agnetix.harnax.agent.service.registrar

import com.agnetix.harnax.agent.service.client.RouterServiceClient
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Agent Service Registrar.
 * Handles registration and heartbeat with the session-router service.
 * On startup, registers this instance with the router.
 * Periodically sends heartbeat to maintain the registration.
 *
 * All HTTP communication with the router is delegated to [RouterServiceClient].
 */
@Component
class AgentServiceRegistrar(
    @Value($$"${agent.service.instance-id}") private val instanceId: String,
    @Value($$"${server.port:8082}") private val port: Int,
    @Value($$"${agent.service.heartbeat-interval-ms:10000}") private val heartbeatIntervalMs: Long,
    private val routerServiceClient: RouterServiceClient,
) {

    private val log = LoggerFactory.getLogger(AgentServiceRegistrar::class.java)
    private var registered = false

    /**
     * Register this agent-service instance with the session-router on startup.
     */
    @PostConstruct
    fun registerOnStartup() {
        try {
            val host = getLocalHost()
            log.info(
                "Registering agent-service instance: $instanceId at $host:$port " +
                    "with router at ${routerServiceClient.getRouterUrl()}",
            )

            if (routerServiceClient.registerInstance(instanceId, host, port)) {
                registered = true
                log.info("Successfully registered with session-router")
            } else {
                log.warn("Failed to register with session-router (will retry)")
            }
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

        if (!routerServiceClient.sendHeartbeat(instanceId)) {
            log.warn("Heartbeat failed for instance $instanceId")
        } else {
            log.debug("Heartbeat sent successfully for instance: $instanceId")
        }
    }

    /**
     * Get the local host address.
     * In Docker/Kubernetes environments, this should be the service name or IP.
     * Enumerates network interfaces to find a real, non-loopback IPv4 address
     * that will pass SSRF validation on the router side.
     */
    private fun getLocalHost(): String {
        // Allow explicit configuration via environment variable
        System.getenv("HOST_IP")?.takeIf { it.isNotBlank() }?.let { return it }

        // Try to detect the real local IP address via network interfaces
        val detectedIp = detectLocalIpFromInterfaces()
        if (detectedIp != null) return detectedIp

        // Fallback: use InetAddress but verify it's not loopback
        return try {
            val addr = InetAddress.getLocalHost()
            if (addr.isLoopbackAddress) {
                throw IllegalStateException("Resolved to loopback address: ${addr.hostAddress}")
            }
            addr.hostAddress
        } catch (e: Exception) {
            log.error(
                "Cannot detect a valid local IP address for registration. " +
                    "Please set HOST_IP environment variable explicitly.",
            )
            throw IllegalStateException("Cannot detect a non-loopback local IP address", e)
        }
    }

    /**
     * Enumerate network interfaces to find a suitable non-loopback IPv4 address.
     * Prefers site-local (private) addresses like 192.168.x.x, 10.x.x.x, 172.16-31.x.x.
     */
    private fun detectLocalIpFromInterfaces(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        var fallbackIp: String? = null

        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue

            for (addr in iface.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress
                    // Prefer site-local (private network) addresses
                    if (addr.isSiteLocalAddress) {
                        log.debug("Detected site-local IP $ip from interface ${iface.displayName}")
                        return ip
                    }
                    // Keep non-site-local as fallback
                    if (fallbackIp == null) {
                        fallbackIp = ip
                    }
                }
            }
        }

        if (fallbackIp != null) {
            log.debug("Using non-site-local IP $fallbackIp as fallback")
        }
        return fallbackIp
    }
}
