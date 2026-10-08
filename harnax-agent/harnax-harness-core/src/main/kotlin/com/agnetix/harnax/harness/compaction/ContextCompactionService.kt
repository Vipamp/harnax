package com.agnetix.harnax.harness.compaction

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.message.Msg
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.memory.MemoryFlushManager
import io.agentscope.harness.agent.memory.compaction.CompactionConfig
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * What one `/compact` command produced, before it becomes a response to the caller.
 *
 * The distinction that matters to the caller is whether the conversation was rewritten: a session too short to
 * compact is still a successful command, and it reports the same numbers on both sides.
 */
sealed interface CompactionOutcome {
    /**
     * @param compacted false when nothing was rewritten and nothing was saved
     */
    data class Success(
        val beforeMessages: Int,
        val afterMessages: Int,
        val beforeTokens: Int,
        val afterTokens: Int,
        val compacted: Boolean,
    ) : CompactionOutcome

    /** The context and the database are untouched, and [message] says why. */
    data class Failed(
        val message: String,
    ) : CompactionOutcome
}

/**
 * Runs compaction on demand for one session.
 *
 * The recipe is the upstream overflow path's — `HarnessAgent.forceCompactAndRetry`, which is `CompactionMiddleware`
 * without the threshold — with both file-writing steps turned off, because a command the user typed should not pay
 * for a second model call they did not ask for. Only the summary call runs: its result replaces the head of the
 * context, and the live state is saved back through the agent's own state store.
 *
 * Only the live state reached through the delegate is a valid subject here. [HarnessAgent] keeps a cache per
 * session slot and `ReActAgent.saveAgentState` writes only a slot already in that cache, so a copy deserialized
 * straight from the database would be modified in one place and saved from another — silently.
 */
object ContextCompactionService {

    private val log = LoggerFactory.getLogger(ContextCompactionService::class.java)

    /**
     * How long the one summarization call may take before the command gives up. The same order as
     * `HarnessConfig.turnTimeoutSeconds`, and well inside the 600s the router allows a JSON proxy to read.
     */
    const val TIMEOUT_SECONDS: Long = 300L

    /**
     * Compacts the session's live context in place.
     *
     * @param agent the assembled agent owning the session; its model writes the summary, its workspace and state
     * store are the ones the conversation already uses
     * @param sessionId the root session
     * @param userId the session's user; null and blank both mean the anonymous bucket the runtime itself uses
     * @param keepTokens tail budget from `/compact <N>`; null keeps the dynamic tier
     * @return [CompactionOutcome.Failed] with nothing written when there is no live state, when the summary did
     * not come back, or when it came back as a failure notice
     */
    fun compact(
        agent: HarnessAgent,
        sessionId: String,
        userId: String?,
        keepTokens: Int? = null,
        timeoutSeconds: Long = TIMEOUT_SECONDS,
    ): CompactionOutcome {
        val delegate = agent.delegate
            ?: return CompactionOutcome.Failed(NO_LIVE_STATE)
        val state = runCatching { delegate.getAgentState(userId, sessionId) }
            .onFailure { log.warn("Could not read the live state of session={}: {}", sessionId, it.message) }
            .getOrNull()
            ?: return CompactionOutcome.Failed(NO_LIVE_STATE)

        val context = state.contextMutable()
        val beforeMessages = context.size
        val beforeTokens = TokenCounterUtil.calculateToken(context)
        if (beforeMessages == 0) {
            return nothingToCompact(beforeMessages, beforeTokens)
        }

        val replacement = try {
            ConversationCompactor(agent.model, MemoryFlushManager(agent.workspaceManager, agent.model))
                .compactIfNeeded(
                    RuntimeContext.builder().sessionId(sessionId).userId(userId ?: "").build(),
                    context,
                    commandConfig(keepTokens),
                    agent.name,
                    sessionId,
                )
                .block(Duration.ofSeconds(timeoutSeconds))
                ?.takeIf { it.isPresent }
                ?.get()
        } catch (e: Exception) {
            log.warn("Compaction of session={} did not complete: {}", sessionId, e.message)
            return CompactionOutcome.Failed(SUMMARY_UNAVAILABLE)
        }
        if (replacement == null) {
            // An empty Optional is how upstream says the trigger was not met and, for a conversation too short
            // to leave a tail, that no safe cutoff exists. Neither is a failed command.
            return nothingToCompact(beforeMessages, beforeTokens)
        }

        val summary = replacement.firstOrNull()
        if (summary == null || summary.name != ConversationCompactor.SUMMARY_MSG_NAME || isFailedSummary(summary)) {
            return CompactionOutcome.Failed(SUMMARY_UNAVAILABLE)
        }

        context.clear()
        context.addAll(replacement)
        delegate.saveAgentState(userId, sessionId)
        val afterTokens = TokenCounterUtil.calculateToken(replacement)
        log.info(
            "Compacted session={}: {} msgs / {} tokens → {} msgs / {} tokens",
            sessionId,
            beforeMessages,
            beforeTokens,
            replacement.size,
            afterTokens,
        )
        return CompactionOutcome.Success(
            beforeMessages = beforeMessages,
            afterMessages = replacement.size,
            beforeTokens = beforeTokens,
            afterTokens = afterTokens,
            compacted = true,
        )
    }

    /**
     * The tier a command runs on: [AutoCompactionTier.command] — the same numbers the automatic path uses, with
     * `triggerMessages(1)` so no threshold can answer a user who asked for this, both file-writing steps off,
     * and `keepTokens` only moving where the tail starts.
     */
    private fun commandConfig(keepTokens: Int?): CompactionConfig = AutoCompactionTier.command(keepTokens)

    /**
     * Upstream swallows a failed summary call into the summary text and still returns a replacement list
     * (`ConversationCompactor.java:375,382`). Trusting that return value on the manual path would trade a working
     * context for an error string the user cannot see, since the page reads the archive rather than the context,
     * and the model would answer from that string on the next turn.
     *
     * Matched as a substring because `buildSummaryMessage` writes its own lead-in in front of the summary body. A
     * false positive costs the user one retry; a false negative leaves the model answering from an error string.
     */
    private fun isFailedSummary(summary: Msg): Boolean = FAILED_SUMMARY_MARKERS.any { summary.textContent?.contains(it) == true }

    private fun nothingToCompact(
        messages: Int,
        tokens: Int,
    ) = CompactionOutcome.Success(
        beforeMessages = messages,
        afterMessages = messages,
        beforeTokens = tokens,
        afterTokens = tokens,
        compacted = false,
    )

    private const val NO_LIVE_STATE = "This session has no live agent state to compact."

    private const val SUMMARY_UNAVAILABLE = "The summary model did not return a summary; the conversation is unchanged."

    private val FAILED_SUMMARY_MARKERS = listOf("(Summarization failed", "(Summary unavailable)")
}
