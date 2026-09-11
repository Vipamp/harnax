package com.agnetix.harnax.router.entity

import java.io.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Agent service instance entity.
 * Represents a registered agent-service instance in the database.
 */
class AgentInstance : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        const val STATUS_UP = "UP"
        const val STATUS_DOWN = "DOWN"
        const val STATUS_DRAINING = "DRAINING"

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

        // Block known cloud metadata service hostnames
        private val BLOCKED_HOST_NAMES = setOf(
            "localhost",
            "metadata.google.internal",
            "metadata.google",
            "metadata",
            "169.254.169.254",
        )

        /**
         * Parse a persisted heartbeat timestamp.
         *
         * Accepts the current epoch-millis format plus the legacy `LocalDateTime.toString()`
         * format, so a rolling router upgrade does not lose heartbeats already stored in Redis.
         */
        fun parseHeartbeat(raw: String?): Instant? {
            if (raw.isNullOrBlank()) return null
            raw.toLongOrNull()?.let { return Instant.ofEpochMilli(it) }
            return try {
                LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: Exception) {
                null
            }
        }

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

            // If it looks like an IP but doesn't match the pattern (e.g. non-numeric), block it
            if (trimmed.matches(Regex("^[^.]+\\.[^.]+\\.[^.]+\\.[^.]+$"))) return true

            return false
        }
    }

    /** Unique instance identifier */
    @Volatile var instanceId: String = ""

    /** Instance host address */
    @Volatile var host: String = ""

    /** Instance port */
    @Volatile var port: Int = 0

    /** Instance status: UP, DOWN, DRAINING */
    @Volatile var status: String = STATUS_UP

    /**
     * Last heartbeat, in UTC epoch. Heartbeat freshness is compared across router nodes, so this
     * must not be a zone-less LocalDateTime — two containers in different timezones would disagree
     * about whether the instance is alive.
     */
    @Volatile var lastHeartbeat: Instant = Instant.now()

    /** Active flag: 0=deleted, 1=active */
    @Volatile var active: Int = 1

    /**
     * Alive: process is up and its heartbeat is fresh.
     *
     * DRAINING counts as alive — draining means "no new sessions", not "dead". Treating it as
     * unhealthy makes the health checker mark the instance DOWN within one check interval and
     * forcibly migrate every session off it, defeating graceful shutdown.
     */
    fun isHealthy(heartbeatTimeoutMs: Long): Boolean {
        if (active != 1) return false
        if (status != STATUS_UP && status != STATUS_DRAINING) return false
        return heartbeatAgeMs() < heartbeatTimeoutMs
    }

    /** Eligible to receive sessions that have no binding yet. */
    fun isAcceptingNewSessions(heartbeatTimeoutMs: Long): Boolean = isHealthy(heartbeatTimeoutMs) && status == STATUS_UP

    /** Age of the last heartbeat; negative when the timestamp is in the future (clock skew). */
    fun heartbeatAgeMs(): Long = ChronoUnit.MILLIS.between(lastHeartbeat, Instant.now())

    fun isDraining(): Boolean = status == STATUS_DRAINING && active == 1

    /**
     * Get the base URL for this instance.
     */
    fun getBaseUrl(): String = "http://$host:$port"
}
