package com.agnetix.harnax.agent.chat

import com.agnetix.harnax.agent.protocol.EventSource

/**
 * @Author: heqingsong
 * @Date: 2026/4/16
 * @Description: MessageLog
 * @Project: harnax
 */
interface MessageLog {
    val role: Role
    val timestamp: Long

    /**
     * The team member run that produced this log, or null when the session's own agent did.
     *
     * A member talks on a child session of its own, so its logs only reach the user because the history
     * endpoint merges them into the root session's list. Without this marker the merge would be
     * indistinguishable from the lead speaking, and the member bubbles the user saw while streaming would
     * silently collapse into the lead's one bubble on reload.
     */
    val source: EventSource?
}

data class SystemMessageLog(
    val message: String,
    override val timestamp: Long = System.currentTimeMillis(),
    override val source: EventSource? = null,
) : MessageLog {
    override val role: Role = Role.SYSTEM
}

data class UserMessageLog(
    val message: String,
    override val timestamp: Long = System.currentTimeMillis(),
    override val source: EventSource? = null,
) : MessageLog {
    override val role: Role = Role.USER
}

data class AssistantMessageLog(
    val thinking: String,
    val text: String,
    val toolUseLog: List<ToolUseLog>,
    override val timestamp: Long = System.currentTimeMillis(),
    override val source: EventSource? = null,
) : MessageLog {
    override val role: Role = Role.ASSISTANT
}

data class ToolUseLog(
    val name: String,
    val input: Map<String, Any>,
)

data class ToolResultMessageLog(
    val name: String,
    val result: String,
    override val timestamp: Long = System.currentTimeMillis(),
    override val source: EventSource? = null,
) : MessageLog {
    override val role: Role = Role.TOOL
}

enum class Role {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL,
}
