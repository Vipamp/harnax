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

    /**
     * Tenant owning the run, from `AgentSpec.tenantId` — every `tool_call_log` row this context produces
     * is stamped with it, the same way it carries [agentId] (V50). Null when the delivery named no
     * tenant, and the row is then stored with NULL rather than a guessed workspace.
     */
    val tenantId: Long? = null,
) : ToolCallContext

data class UserIdentifier(
    /** End user behind the call; null when the caller is a service or a key without an owner. */
    val userId: Long? = null,
) : ToolCallContext
