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
