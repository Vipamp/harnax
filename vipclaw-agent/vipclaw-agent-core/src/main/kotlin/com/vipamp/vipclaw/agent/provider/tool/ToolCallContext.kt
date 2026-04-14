package com.vipamp.vipclaw.agent.provider.tool

/**
 * @Author: heqingsong
 * @Date: 2026/4/14
 * @Description: ToolCallContext
 * @Project: vipclaw
 */
interface ToolCallContext

data class SessionMetaContext(
    val agentId: Long,
    val sessionId: String
) : ToolCallContext

data class UserIdentifier(
    val userId: Long,
) : ToolCallContext
