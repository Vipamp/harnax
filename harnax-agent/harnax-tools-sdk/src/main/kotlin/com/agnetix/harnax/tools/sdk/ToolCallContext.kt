package com.agnetix.harnax.tools.sdk

/**
 * @Author: heqingsong
 * @Date: 2026/4/14
 * @Description: ToolCallContext
 * @Project: harnax
 */
interface ToolCallContext

data class SessionMetaContext(
    /** Null when no `agent` row stands behind the run — a team's lead. See `AgentSpec.attributableAgentId`. */
    val agentId: Long?,
    val sessionId: String,
) : ToolCallContext

data class UserIdentifier(
    /** End user behind the call; null when the caller is a service or a key without an owner. */
    val userId: Long? = null,
) : ToolCallContext
