package com.agnetix.harnax.agent.service.runner

import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.chat.MessageLogConverter
import com.agnetix.harnax.agent.chat.Role
import com.agnetix.harnax.agent.protocol.EventSource
import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.harness.HarnessAgentLauncher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Replays team member conversations into the chat history of the root session.
 *
 * A member runs on a child session of its own (`team-<root>-m<memberAgentId>`) and its full conversation
 * persists there, while `/chat/history` is asked for the root session only. Without this merge the member
 * bubbles the user watched being written live collapse into the lead's single bubble on reload, and the
 * only trace of the member's work is the text inside one `team_delegate` tool result.
 *
 * The replayed logs are stamped with [EventSource] so the UI can tell who spoke. They go in one flat list
 * because that is what the router passes through untouched (`ResultVo<List<Any>>`) and what every other
 * consumer of this endpoint already parses.
 */
@Component
class TeamHistoryReplay(
    private val launcher: HarnessAgentLauncher,
    private val adminApiClient: AdminApiClient,
) {

    private val log = LoggerFactory.getLogger(TeamHistoryReplay::class.java)

    /**
     * Returns [leadLogs] with every member's persisted conversation merged in, or [leadLogs] unchanged
     * when the session has no member child sessions.
     */
    fun merge(
        sessionId: String,
        leadLogs: List<MessageLog>,
    ): List<MessageLog> {
        val childSessionIds = launcher.memberSessionIds(sessionId)
        if (childSessionIds.isEmpty()) return leadLogs
        val sources = resolveSources(sessionId, childSessionIds) ?: return leadLogs
        val memberLogs = sources.flatMap { (childSessionId, source) ->
            launcher.loadSessionMessages(childSessionId)
                .flatMap { MessageLogConverter.convert(it, source) }
                .filter { it.role == Role.ASSISTANT || it.role == Role.TOOL }
        }
        if (memberLogs.isEmpty()) return leadLogs
        log.info(
            "Replaying team history: session={}, memberSessions={}, memberLogs={}",
            sessionId,
            sources.keys,
            memberLogs.size,
        )
        return interleave(leadLogs, memberLogs)
    }

    /**
     * child session id -> its provenance, from the roster admin resolves for this root session.
     *
     * Null on any failure: a team whose configuration can no longer be read must still show the lead's
     * history rather than fail the whole request. The names come from admin because a member can be
     * renamed after its child session was written, and the user should see who it is now.
     */
    private fun resolveSources(
        sessionId: String,
        childSessionIds: List<String>,
    ): Map<String, EventSource>? = try {
        val teamSpec = adminApiClient.getTeamSpec(sessionId)
        teamSpec.members
            .mapNotNull { member ->
                val childSessionId = launcher.memberSessionId(sessionId, member.memberAgentId)
                if (childSessionId !in childSessionIds) return@mapNotNull null
                childSessionId to EventSource(
                    teamId = teamSpec.teamId,
                    teamName = teamSpec.teamName,
                    memberAgentId = member.memberAgentId,
                    memberAgentName = member.agentName,
                    // No run id was ever persisted, so the child session stands in for it: it identifies
                    // the member across reloads, which is all the UI needs to group a replayed bubble.
                    childRunId = childSessionId,
                    childSessionId = childSessionId,
                )
            }
            .toMap()
    } catch (e: Exception) {
        log.warn("Cannot resolve team roster for history replay, session={}: {}", sessionId, e.message)
        null
    }

    /**
     * Interleaves member logs into the lead's by timestamp, never splitting an assistant message from the
     * tool results that belong to it.
     *
     * Delegation is foreground and blocking, so a member's whole run falls strictly between the lead's
     * `team_delegate` message and the lead's next one. That makes the lead's own turn the insertion point:
     * member logs surface after the block they were produced under, which is the order the user watched.
     * A naive timestamp sort would instead slide them between an assistant message and its tool results
     * and break the pairing the UI relies on.
     */
    fun interleave(
        leadLogs: List<MessageLog>,
        memberLogs: List<MessageLog>,
    ): List<MessageLog> {
        val blocks = splitTurns(leadLogs)
        // Member logs move a whole turn at a time. Two members may run at the same time, so sorting their
        // logs as one flat sequence would let one member's message land between another member's assistant
        // message and its own tool result, and the UI renders that pair as a tool card which never resolves.
        val pending = splitTurns(memberLogs).sortedBy { it.first().timestamp }.toMutableList()
        val merged = mutableListOf<MessageLog>()
        for (block in blocks) {
            val nextStart = block.first().timestamp
            while (pending.isNotEmpty() && pending[0].first().timestamp < nextStart) {
                merged.addAll(pending.removeAt(0))
            }
            merged.addAll(block)
        }
        // A member run that outlived the lead's last persisted message (interrupted, or the lead never
        // got to summarise it) still belongs on screen.
        for (turn in pending) merged.addAll(turn)
        return merged
    }

    /**
     * Groups logs into one turn each: an assistant message plus the tool results that follow it,
     * with user and system messages standing alone.
     */
    private fun splitTurns(logs: List<MessageLog>): List<List<MessageLog>> {
        val blocks = mutableListOf<MutableList<MessageLog>>()
        for (logEntry in logs) {
            val current = blocks.lastOrNull()
            val continuesCurrentBlock = logEntry.role == Role.TOOL &&
                current != null &&
                current.any { it.role == Role.ASSISTANT }
            if (continuesCurrentBlock) current!!.add(logEntry) else blocks.add(mutableListOf(logEntry))
        }
        return blocks
    }
}
