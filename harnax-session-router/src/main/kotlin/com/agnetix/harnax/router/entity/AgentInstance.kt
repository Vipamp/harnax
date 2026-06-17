package com.agnetix.harnax.router.entity

import java.io.Serializable
import java.time.LocalDateTime

/**
 * Agent service instance entity.
 * Represents a registered agent-service instance in the database.
 */
class AgentInstance : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        // Allowed port range for agent services (8000-9999)
        const val MIN_PORT = 8000
        const val MAX_PORT = 9999

        // Block loopback addresses
        private val LOOPBACK_PATTERNS = listOf(
            Regex("^127\\."),
            Regex("^0\\."),
            Regex("^::1$"),
            Regex("^fe80:", RegexOption.IGNORE_CASE),
        )

        // Block link-local addresses (cloud metadata endpoints like 169.254.169.254)
        private val LINK_LOCAL_PATTERNS = listOf(
            Regex("^169\\.254\\."),
        )

        // Block known cloud metadata service hostnames
        private val BLOCKED_HOST_NAMES = setOf(
            "localhost",
            "metadata.google.internal",
            "metadata.google",
            "metadata",
            "169.254.169.254",
        )

        /**
         * Validate if host is a valid IP address (not domain name).
         * Only allows IPv4 addresses to prevent DNS rebinding attacks.
         */
        fun isValidIpAddress(host: String): Boolean {
            val trimmed = host.trim()

            // Reject domain names - only allow IP addresses
            if (trimmed.contains(Regex("[a-zA-Z]"))) {
                return false
            }

            // Validate IPv4 format
            val ipv4Pattern = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
            val matchResult = ipv4Pattern.matchEntire(trimmed) ?: return false

            try {
                val (a, b, c, d) = matchResult.destructured
                val octets = listOf(a.toInt(), b.toInt(), c.toInt(), d.toInt())
                return octets.all { it in 0..255 }
            } catch (e: NumberFormatException) {
                return false
            }
        }

        /**
         * Check if a port is within the allowed range.
         */
        fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

        /**
         * Check if a host is blocked for security reasons.
         * Blocks loopback, link-local, and known cloud metadata endpoints.
         * Private network addresses (10.x, 172.16-31.x, 192.168.x) are allowed
         * since agent-service instances typically run on internal networks.
         */
        fun isBlockedHost(host: String): Boolean {
            val trimmed = host.trim().lowercase()

            if (trimmed in BLOCKED_HOST_NAMES) return true

            if (LOOPBACK_PATTERNS.any { it.containsMatchIn(trimmed) }) return true

            val ipv4Pattern = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
            val matchResult = ipv4Pattern.matchEntire(trimmed)
            if (matchResult != null) {
                val (a, b, c, d) = matchResult.destructured
                try {
                    val octets = listOf(a.toInt(), b.toInt(), c.toInt(), d.toInt())
                    if (octets.any { it < 0 || it > 255 }) return true

                    if (a == "0" || a == "127") return true

                    if (a == "169" && b == "254") return true

                    return false
                } catch (e: NumberFormatException) {
                    return true
                }
            }

            return false
        }
    }

    var id: Long = 0

    /** Unique instance identifier */
    var instanceId: String = ""

    /** Instance host address */
    var host: String = ""

    /** Instance port */
    var port: Int = 0

    /** Instance status: UP, DOWN, DRAINING */
    var status: String = "UP"

    /** Last heartbeat timestamp */
    var lastHeartbeat: LocalDateTime = LocalDateTime.now()

    /** Active flag: 0=deleted, 1=active */
    var active: Int = 1

    /** Creation time */
    var createTime: LocalDateTime = LocalDateTime.now()

    /** Update time */
    var updateTime: LocalDateTime = LocalDateTime.now()

    /**
     * Check if instance is healthy based on heartbeat timeout.
     */
    fun isHealthy(heartbeatTimeoutMs: Long): Boolean {
        if (status != "UP" || active != 1) return false
        val timeoutSeconds = heartbeatTimeoutMs / 1000
        return lastHeartbeat.isAfter(LocalDateTime.now().minusSeconds(timeoutSeconds))
    }

    fun isDraining(): Boolean = status == "DRAINING" && active == 1

    /**
     * Get the base URL for this instance.
     */
    fun getBaseUrl(): String = "http://$host:$port"
}
