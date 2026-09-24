package com.agnetix.harnax.admin.util

import com.agnetix.harnax.admin.service.SchedulerClient
import com.agnetix.harnax.common.dto.AgentTaskOwner
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
 * resolved here, from the same session rows
 * [com.agnetix.harnax.admin.controller.InternalApiController] resolves an agent spec from — except for
 * a task session, whose row is leaving this service's database with the scheduled-task domain and is
 * read from the scheduler instead (contract C5, see [fromTask]).
 */
data class McpSessionOwner(
    val userId: Long,
    val tenantId: Long,
)

@Component
class McpSessionOwnerResolver(
    private val sessionMapper: SessionMapper,
    private val schedulerClient: SchedulerClient,
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
        // The first segment is all this reads, so the agent id C1 put in front of the tail stays invisible
        // here — which is the point: the owner is a person, and the agent is not.
        return taskOwner(taskId, sessionId)?.let { ownerOf(it.creator, it.tenantId, sessionId) }
    }

    /**
     * Contract C5: release 2 moves `agent_task` into the scheduler's own database, so the creator and
     * tenant that decide whose OAuth grant a task session may spend are read over HTTP. Cold path by
     * design — this runs when an agent builds an OAuth MCP client, and never on the agent-spec lookup
     * every message goes through.
     *
     * Null for every way that read can fail: the scheduler refused or did not answer, it has no such task,
     * or the client itself blew up. Each one is reported at WARN with the task id and the reason, because
     * nothing downstream sees a failure — [resolve] answers nobody, the run goes on without that tool, and
     * "the scheduler has been down since 03:00" would otherwise read as "this task has no OAuth MCPs".
     * An exception in particular must not escape into the execution.
     */
    private fun taskOwner(
        taskId: Long,
        sessionId: String,
    ): AgentTaskOwner? {
        val answer = try {
            schedulerClient.taskOwner(taskId)
        } catch (e: Exception) {
            log.warn("Task {} owner lookup for session {} failed, so no MCP identity is used: {}", taskId, sessionId, e.message)
            return null
        }
        if (!answer.isSuccess()) {
            log.warn("Task {} owner lookup for session {} did not succeed, so no MCP identity is used: {}", taskId, sessionId, answer.message)
            return null
        }
        if (answer.data == null) {
            log.warn("Task {} named by session {} has no row the scheduler can read, so no MCP identity is used", taskId, sessionId)
        }
        return answer.data
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
        // `status` is the account switch and `selectByUsername` does not filter it — it cannot, since
        // login runs the same query and has to say "account disabled" rather than "no such user".
        // A session opened before the switch was thrown must not keep spending its owner's grants.
        if (user.status != 1) {
            log.info(
                "Session {} creator '{}' (user {}) is disabled, so no MCP identity is used",
                sessionId,
                username,
                user.id,
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
