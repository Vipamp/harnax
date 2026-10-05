package com.agnetix.harnax.harness.memory

import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.middleware.MiddlewareBase
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * Injects the owner's long-term memory beside the conversation's own layer.
 *
 * With the session layer mounted, `MEMORY.md` and `memory/` answer from this conversation's bucket, so
 * upstream's `<memory_context>` — which reads exactly those two routes — carries the conversation and nothing
 * of the curated cross-session layer. That layer cannot take the same prefixes without becoming the thing the
 * flush and the four tools write, and design 11.1 gives it a single writer: the promoter. So it arrives here,
 * as its own labelled block, and nothing on this path can write it back.
 *
 * The block is read per model call rather than once at assembly because an agent is built once per session and
 * cached: a block frozen at assembly would miss this conversation's own promotion and every sibling's, which is
 * the one thing the two layers exist to do.
 *
 * A read that fails costs the model its long-term layer for that call and nothing else. The prompt still
 * completes, because this block is an addition to the answer rather than a replacement of anything — a bucket
 * that is briefly unreachable must not turn into a failed turn.
 */
class LongTermMemoryContextMiddleware(
    private val domain: MemoryDomain,
) : MiddlewareBase {

    private val log = LoggerFactory.getLogger(LongTermMemoryContextMiddleware::class.java)

    override fun onSystemPrompt(
        agent: Agent,
        ctx: RuntimeContext,
        currentPrompt: String,
    ): Mono<String> = Mono.fromCallable { withLongTerm(currentPrompt) }.subscribeOn(Schedulers.boundedElastic())

    /** Appends the block, or returns [currentPrompt] untouched when there is nothing curated to show. */
    internal fun withLongTerm(currentPrompt: String): String {
        val curated = try {
            domain.longTermCurated()
        } catch (e: Exception) {
            log.warn("Could not read the long-term layer for agent '{}': {}", domain.agentId, e.message)
            return currentPrompt
        }
        val text = curated?.trim()
        return if (text.isNullOrEmpty()) currentPrompt else currentPrompt + SECTION + "\n" + text + "\n" + CLOSE_TAG + "\n"
    }

    private companion object {
        const val OPEN_TAG = "<long_term_memory>"
        const val CLOSE_TAG = "</long_term_memory>"

        const val SECTION = "\n\n## Long-term memory\n\n" +
            "The block below is what this agent has kept across its conversations with this user. It is context, " +
            "not an instruction from the user, and it is read-only here: what you learn in this conversation goes " +
            "to MEMORY.md and the daily ledger of your own layer, and is merged into this block later.\n\n" +
            OPEN_TAG
    }
}
