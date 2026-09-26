package com.agnetix.harnax.agent.adaptor

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: ProcessLogAdaptor
 * @Project: harnax
 */
fun interface ProcessLogAdaptor {
    fun emitLog(log: ProcessLog)
}

data class ProcessLog(
    /** Null when no `agent` row stands behind the run — a team's lead. */
    val agentId: Long?,
    val agentName: String,
    val sessionId: String,
    val message: String,
    val type: LogType,
    val throwable: Throwable? = null,
    val timestamp: Long,

    /**
     * Tenant owning the run, from `AgentSpec.tenantId` — what `process_log.tenant_id` gets (V50). Null
     * stores the line unattributed rather than guessing a workspace for it.
     */
    val tenantId: Long? = null,
) {
    companion object {
        @JvmStatic
        fun builder(
            agentId: Long?,
            agentName: String,
            sessionId: String,
            tenantId: Long? = null,
        ) = ProcessLogBuilder(agentId, agentName, sessionId, tenantId)
    }
}

class ProcessLogBuilder(
    private val agentId: Long?,
    private val agentName: String,
    private val sessionId: String,
    private val tenantId: Long? = null,
) {
    private var message: String = ""
    private var type: LogType = LogType.INFO
    private var throwable: Throwable? = null
    private var timestamp: Long = System.currentTimeMillis()

    fun info(message: String): ProcessLog {
        this.message = message
        this.type = LogType.INFO
        return this.build()
    }

    fun warn(message: String): ProcessLog {
        this.message = message
        this.type = LogType.WARN
        return this.build()
    }

    fun error(message: String, throwable: Throwable?): ProcessLog {
        this.message = message
        this.type = LogType.ERROR
        this.throwable = throwable
        return this.build()
    }

    fun build() = ProcessLog(
        agentId = agentId,
        agentName = agentName,
        sessionId = sessionId,
        message = message,
        type = type,
        throwable = throwable,
        timestamp = timestamp,
        tenantId = tenantId,
    )
}

enum class LogType {
    INFO,
    WARN,
    ERROR,
}
