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

        // Block loopback addresses
        private val LOOPBACK_PATTERNS = listOf(
            Regex("^127\\."),
            Regex("^0\\."),
            Regex("^::1$"),
            Regex("^fe80:", RegexOption.IGNORE_CASE),
        )

        // Block link-local addresses
        private val LINK_LOCAL_PATTERNS = listOf(
            Regex("^169\\.254\\."),
        )

        // Block private network ranges (RFC 1918)
        private val PRIVATE_NETWORK_PATTERNS = listOf(
            Regex("^10\\."),
            Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\."),
            Regex("^192\\.168\\."),
        )

        // Block metadata and internal services
        private val BLOCKED_HOST_NAMES = setOf(
            "localhost",
            "metadata.google.internal",
            "metadata.google",
            "metadata",
            "169.254.169.254",
        )

        /**
         * Check if a host is blocked for security reasons.
         * Blocks loopback, link-local, private networks, and known metadata endpoints.
         */
        fun isBlockedHost(host: String): Boolean {
            val trimmed = host.trim().lowercase()
            
            // Check against blocked names first
            if (trimmed in BLOCKED_HOST_NAMES) return true
            
            // Check IPv6 patterns
            if (LOOPBACK_PATTERNS.any { it.containsMatchIn(trimmed) }) return true
            
            // Check IPv4 patterns
            val ipv4Pattern = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
            val matchResult = ipv4Pattern.matchEntire(trimmed)
            if (matchResult != null) {
                // Validate IP octets
                val (a, b, c, d) = matchResult.destructured
                try {
                    val octets = listOf(a.toInt(), b.toInt(), c.toInt(), d.toInt())
                    if (octets.any { it < 0 || it > 255 }) return true
                    
                    // Check loopback (0.x.x.x, 127.x.x.x)
                    if (a == "0" || a == "127") return true
                    
                    // Check link-local (169.254.x.x)
                    if (a == "169" && b == "254") return true
                    
                    // Check private networks (RFC 1918)
                    if (a == "10") return true
                    if (a == "172" && b.toInt() in 16..31) return true
                    if (a == "192" && b == "168") return true
                    
                    return false
                } catch (e: NumberFormatException) {
                    return true // Invalid IP format
                }
            }
            
            // For domain names, block known metadata endpoints
            if (trimmed.endsWith(".internal") || trimmed.endsWith(".local")) return true
            
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
