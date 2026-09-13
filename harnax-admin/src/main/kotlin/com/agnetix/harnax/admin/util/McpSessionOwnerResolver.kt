package com.agnetix.harnax.admin.util

import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SysUserMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The person a runtime session belongs to, as `sys_user.id` — the key `mcp_user_credential` is
 * indexed by (design section 3.4 and 7.2).
 *
 * agent-service asks for an MCP token with a session id and nothing else: it must not learn who the
 * user is beyond what it already renders, and it must not be able to name one. So the identity is
 * resolved here, from storage the admin owns, from the same session rows
 * [com.agnetix.harnax.admin.controller.InternalApiController] resolves an agent spec from.
 */
data class McpSessionOwner(
    val userId: Long,
    val tenantId: Long,
)

@Component
class McpSessionOwnerResolver(
    private val sessionMapper: SessionMapper,
    private val agentTaskMapper: AgentTaskMapper,
    private val sysUserMapper: SysUserMapper,
) {

    private val log = LoggerFactory.getLogger(McpSessionOwnerResolver::class.java)

    /**
     * Null when the session has no OAuth-capable human owner. That is not an error to report as one:
     * `chn-` sessions are a DingTalk/Feishu conversation with a channel sender id in `creator`, and a
     * scheduled task belongs to whoever created the task definition — presenting *that* person's
     * grant to a channel message would be someone else's identity reaching an MCP server. Callers
     * refuse, per the rule the design sets: no user, no OAuth tool.
     */
    fun resolve(sessionId: String): McpSessionOwner? = when {
        sessionId.startsWith(WEB_PREFIX) || sessionId.startsWith(MP_PREFIX) -> fromSession(sessionId)
        sessionId.startsWith(TASK_PREFIX) -> fromTask(sessionId)
        sessionId.startsWith(CHANNEL_PREFIX) -> {
            log.debug("Session {} is a channel conversation: no user identity, OAuth MCP tools are not available", sessionId)
            null
        }

        else -> {
            log.warn("Session {} has an unknown prefix, so its owner cannot be resolved", sessionId)
            null
        }
    }

    private fun fromSession(sessionId: String): McpSessionOwner? = sessionMapper
        .selectBySessionIdAndStatus(sessionId, ACTIVE_SESSION_STATUS)
        ?.let { ownerOf(it.creator, it.tenantId, sessionId) }

    private fun fromTask(sessionId: String): McpSessionOwner? {
        val taskId = sessionId.removePrefix(TASK_PREFIX).substringBefore('-').toLongOrNull()
            ?: run {
                log.warn("Task session {} carries no parsable task id, so its owner cannot be resolved", sessionId)
                return null
            }
        return agentTaskMapper.selectAnyById(taskId)?.let { ownerOf(it.creator, it.tenantId, sessionId) }
    }

    /**
     * `creator` holds a username for a web session and the numeric `sys_user.id` for a mini-program one
     * (`MpSessionService` writes `userId.toString()`), so both are tried. The id matters because a
     * grant is keyed by `mcp_user_credential.user_id`: a rename must not orphan a grant or hand it to
     * whoever takes the name next, and a creator that resolves to no row has no grant to spend - which
     * is the right answer, not a lookup by name.
     */
    private fun ownerOf(
        creator: String?,
        tenantId: Long,
        sessionId: String,
    ): McpSessionOwner? {
        val username = creator?.trim().orEmpty()
        if (username.isEmpty()) {
            log.debug("Session {} carries no creator, so it has no MCP identity to use", sessionId)
            return null
        }
        val user = sysUserMapper.selectByUsername(username)
            // Only a plain number is treated as an id, and only after the username match failed: a
            // login literally named "12345" still wins as itself.
            ?: username.toLongOrNull()?.let { sysUserMapper.selectById(it) }
        if (user == null) {
            log.debug("Session {} was created by '{}', which no longer resolves to a user", sessionId, username)
            return null
        }
        // `username` carries no unique index, so a same-named account in another tenant is a real
        // possibility, and resolving to it would spend that person's grant under this session. A row
        // with no tenant of its own cannot be scoped this way and is taken as-is.
        if (user.tenantId != null && user.tenantId != tenantId) {
            log.warn(
                "Session {} creator '{}' resolves to user {} outside tenant {}, so no MCP identity is used",
                sessionId,
                username,
                user.id,
                tenantId,
            )
            return null
        }
        return McpSessionOwner(userId = user.id, tenantId = tenantId)
    }

    companion object {
        private const val WEB_PREFIX = "web-"
        private const val MP_PREFIX = "mp-"
        private const val TASK_PREFIX = "task-"
        private const val CHANNEL_PREFIX = "chn-"

        /** Same status the agent-spec resolution uses: 1 = active session. */
        private const val ACTIVE_SESSION_STATUS = 1
    }
}
