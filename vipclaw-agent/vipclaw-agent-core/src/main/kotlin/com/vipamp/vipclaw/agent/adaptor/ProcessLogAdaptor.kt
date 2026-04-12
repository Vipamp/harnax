package com.vipamp.vipclaw.agent.adaptor

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: ProcessLogAdaptor
 * @Project: vipclaw
 */
fun interface ProcessLogAdaptor {
    fun emitLog(log: ProcessLog)
}

data class ProcessLog(
    val agentId: Long,
    val agentName: String,
    val sessionId: String,
    val message: String,
    val type: LogType,
    val throwable: Throwable? = null,
    val timestamp: Long,
) {
    companion object {
        @JvmStatic
        fun builder(agentId: Long, agentName: String, sessionId: String) =
            ProcessLogBuilder(agentId, agentName, sessionId)
    }
}

class ProcessLogBuilder(
    private val agentId: Long, private val agentName: String, private val sessionId: String
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
        timestamp = timestamp
    )
}

enum class LogType {
    INFO, WARN, ERROR
}
