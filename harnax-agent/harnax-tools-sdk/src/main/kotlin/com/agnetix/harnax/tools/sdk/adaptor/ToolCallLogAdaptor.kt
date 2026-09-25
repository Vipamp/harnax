package com.agnetix.harnax.tools.sdk.adaptor

/**
 * @Author: heqingsong
 * @Date: 2026/4/14
 * @Description: ToolCallLog
 * @Project: harnax
 */
fun interface ToolCallLogAdaptor {
    fun emit(toolCallInfo: ToolCallInfo)
}

data class ToolCallInfo(
    /** Null when no `agent` row stands behind the call — a team's lead. */
    val agentId: Long?,
    val sessionId: String,
    val toolName: String,
    val args: Map<String, String>,
    val result: String,
    val success: Boolean = true,
    val startTime: Long,
    val endTime: Long,
    val duration: Long,
)
