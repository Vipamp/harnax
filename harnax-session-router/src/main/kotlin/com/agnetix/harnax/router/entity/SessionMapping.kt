package com.agnetix.harnax.router.entity

import java.io.Serializable
import java.time.LocalDateTime

/**
 * Session mapping entity.
 * Maps a session to a specific agent-service instance.
 */
class SessionMapping : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    var id: Long = 0

    /** Session identifier */
    var sessionId: String = ""

    /** Bound agent-service instance ID */
    var instanceId: String = ""

    /** Associated agent ID */
    var agentId: Long? = null

    /** Last activity timestamp */
    var lastActiveTime: LocalDateTime = LocalDateTime.now()

    /** Active flag: 0=deleted, 1=active */
    var active: Int = 1

    /** Creation time */
    var createTime: LocalDateTime = LocalDateTime.now()

    /** Update time */
    var updateTime: LocalDateTime = LocalDateTime.now()
}
