package com.vipamp.vipclaw.agent.chat

/**
 * @Author: heqingsong
 * @Date: 2026/4/16
 * @Description: MessageLog
 * @Project: vipclaw
 */
interface MessageLog {
    val role: Role
    val timestamp: Long
}

data class SystemMessageLog(
    val message: String,
    override val timestamp: Long = System.currentTimeMillis()
) : MessageLog {
    override val role: Role = Role.SYSTEM
}

data class UserMessageLog(
    val message: String,
    override val timestamp: Long = System.currentTimeMillis()
) : MessageLog {
    override val role: Role = Role.USER
}

data class AssistantMessageLog(
    val thinking: String,
    val text: String,
    val toolUseLog: List<ToolUseLog>,
    override val timestamp: Long = System.currentTimeMillis()
) : MessageLog {
    override val role: Role = Role.ASSISTANT
}

data class ToolUseLog(
    val name: String,
    val input: Map<String, Any>
)

data class ToolResultMessageLog(
    val name: String,
    val result: String,
    override val timestamp: Long = System.currentTimeMillis()
) : MessageLog {
    override val role: Role = Role.TOOL
}

enum class Role {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}
