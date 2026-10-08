package com.agnetix.harnax.harness.memory

import com.agnetix.harnax.agent.adaptor.MemoryDraftAdaptor
import com.agnetix.harnax.agent.adaptor.MemoryDraftIntake
import com.agnetix.harnax.agent.adaptor.MemoryDraftProposal
import com.agnetix.harnax.agent.adaptor.MemoryDraftSource
import io.agentscope.core.message.ContentBlock
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.model.Model
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import io.agentscope.harness.agent.memory.MemoryConsolidator
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Turns one conversation's own memory layer into a proposal for its owner's long-term layer.
 *
 * The conversation layer is a buffer, not an archive: it is what the flush and the curation pass write while
 * the conversation is hot, and it has no reader outside this conversation (design 11.1). Its content becomes
 * cross-session knowledge only when a person approves the merge this class files (design 11.4), because the
 * result is the block [LongTermMemoryContextMiddleware] injects into every later conversation of that owner.
 *
 * So this class reads and asks; it never writes. That is the whole difference from an automatic promotion:
 *
 * The long-term layer is not touched here at all. Writing it is the approval's job, and the approval applies
 * this text to the exact version it was merged against — [MemoryDraftProposal.baseVersion] is recorded so a
 * layer that moved in the meantime can be refused rather than quietly overwritten by whoever decides first.
 *
 * The conversation layer is not cleared here either. Until a decision exists, this layer holds the only copy
 * of what has not been curated, and a queue nobody has read yet is not a reason to throw it away. The objects
 * this merge took material out of travel in the proposal with the bytes they held, which is what lets the
 * approval clear exactly them and nothing that took a write since.
 *
 * The merge reuses upstream's consolidation prompt verbatim: its two declared inputs are "the current
 * MEMORY.md" and "new entries to merge", and a conversation's layer is exactly the second. The prohibitions
 * come from [MemoryConfigFactory] so a merge cannot launder a secret or another tenant's data into a proposal
 * a reviewer will read.
 */
class MemoryPromoter(
    private val domain: MemoryDomain,
    private val sessionId: String,
    private val model: Model,
    private val draftAdaptor: MemoryDraftAdaptor,
    private val maxMemoryTokens: Int = MemoryConfigFactory.CONSOLIDATION_MAX_TOKENS,
    private val modelTimeout: Duration = DEFAULT_MODEL_TIMEOUT,
) {

    private val log = LoggerFactory.getLogger(MemoryPromoter::class.java)

    /** What one attempt did, and with it whether a reviewer has anything to decide. */
    enum class Outcome {

        /** The layer held nothing to merge, so nothing was read and no model call was spent. */
        NOTHING,

        /** The merge was filed; a person now decides it. */
        QUEUED,

        /** The model failed, ran out of the time one merge gets, or answered with text that drops the owner's. */
        MODEL_FAILED,

        /** The store refused one of the two reads this merge is built from. */
        STORE_FAILED,

        /** Admin took the proposal and said it will not enter the queue — retrying files the same refusal. */
        REFUSED,

        /** Admin or the network could not be reached, so nothing was filed and the layer is kept for later. */
        UNAVAILABLE,
    }

    /**
     * What one conversation's layer holds, read once per attempt.
     *
     * Every object carries the body this pass read, which is what the approval later compares against before
     * it removes anything.
     */
    private class SessionLayer(
        val curated: Draft?,
        val ledgers: List<Ledger>,
    ) {
        fun isEmpty(): Boolean = curated?.text.isNullOrBlank() && ledgers.isEmpty()

        /** How many objects one merge took material out of, which is how many an approval could clear. */
        fun objectCount(): Int = (if (curated?.text.isNullOrBlank()) 0 else 1) + ledgers.size

        /**
         * What this merge read, as paths a reviewer and an approval both resolve inside one conversation's layer.
         *
         * The curated draft goes first so a queue that lists the objects in this order reads the way the layer
         * is laid out. Blank objects are left out because an approval clears what a merge took material from,
         * and there is no material in an empty file to have taken.
         */
        fun sources(): List<MemoryDraftSource> = buildList {
            curated?.text?.takeIf { it.isNotBlank() }?.let {
                add(MemoryDraftSource(MemoryFilesystemRoutes.MEMORY_MD_ROUTE, it))
            }
            ledgers.forEach { add(MemoryDraftSource(MemoryFilesystemRoutes.MEMORY_DIR_ROUTE + it.name, it.text)) }
        }
    }

    /** This conversation's un-merged draft. */
    private class Draft(val text: String?)

    /** One daily ledger of this conversation: what it is called and what this pass read in it. */
    private class Ledger(val name: String, val text: String)

    /**
     * Whether this conversation's layer holds anything a merge could take material out of — cheap, and asking
     * the same question [proposeNow] will ask two store round trips later.
     *
     * [MemoryPromotionMiddleware] asks this before it claims the throttle slot, because a claim cannot be
     * handed back: the gate has one method, and winning it writes the timestamp that closes the window. The
     * extraction that fills this layer is itself dispatched after the answer and needs a model round trip, so
     * the first turn of a conversation finds it empty as a rule. Claiming on that empty read would burn the
     * whole window on nothing.
     *
     * A layer that cannot be read is answered the same way as an empty one: no claim. The window belongs to
     * whoever fills the layer first.
     */
    fun hasUnpromotedContent(): Boolean = try {
        !SessionLayer(readCurated(domain.curatedNamespace(sessionId)), readLedgers()).isEmpty()
    } catch (e: Exception) {
        log.warn(
            "Could not check whether conversation '{}' of agent '{}' has anything to promote, so its throttle " +
                "window is left alone: {}",
            sessionId,
            domain.agentId,
            e.message,
        )
        false
    }

    /**
     * One proposal attempt, start to finish, called by [MemoryPromotionMiddleware] off the conversation path.
     *
     * Synchronous on purpose: the caller puts it on a blocking-friendly scheduler and every step of it is a
     * blocking store, model or HTTP round trip. Splitting it into a reactive chain would move the same waits
     * around while making the one thing that matters here — which bytes a reviewer is being asked to approve —
     * harder to read.
     */
    internal fun proposeNow(): Outcome {
        val session = try {
            SessionLayer(readCurated(domain.curatedNamespace(sessionId)), readLedgers())
        } catch (e: Exception) {
            log.warn(
                "Could not read conversation '{}' of agent '{}' for promotion, so nothing was proposed: {}",
                sessionId,
                domain.agentId,
                e.message,
            )
            return Outcome.STORE_FAILED
        }
        if (session.isEmpty()) {
            log.debug("Nothing to propose out of conversation '{}' of agent '{}'", sessionId, domain.agentId)
            return Outcome.NOTHING
        }

        val longTerm = try {
            store.get(domain.curatedNamespace(null), MemoryFilesystemRoutes.CURATED_ITEM_KEY)
        } catch (e: Exception) {
            log.warn(
                "Could not read the long-term layer of agent '{}', so conversation '{}' proposed nothing: {}",
                domain.agentId,
                sessionId,
                e.message,
            )
            return Outcome.STORE_FAILED
        }

        val merged = ask(longTerm, session) ?: return Outcome.MODEL_FAILED
        return when (
            val intake = draftAdaptor.propose(
                MemoryDraftProposal(
                    sessionId = sessionId,
                    agentName = domain.agentId,
                    mergedMarkdown = merged,
                    baseMarkdown = contentOf(longTerm)?.takeIf { it.isNotBlank() },
                    baseVersion = longTerm?.version() ?: CREATE_IF_ABSENT,
                    sources = session.sources(),
                ),
            )
        ) {
            is MemoryDraftIntake.Queued -> {
                log.info(
                    "Proposed promoting conversation '{}' of agent '{}' into its long-term layer ({} chars " +
                        "merged from {} layer object(s), draft {} awaits review)",
                    sessionId,
                    domain.agentId,
                    merged.length,
                    session.objectCount(),
                    intake.draftId,
                )
                Outcome.QUEUED
            }

            is MemoryDraftIntake.Refused -> {
                log.warn(
                    "Admin refused the memory proposal of conversation '{}' of agent '{}': {} — the layer is " +
                        "kept, and a refusal this conversation cannot answer will repeat next window",
                    sessionId,
                    domain.agentId,
                    intake.reason,
                )
                Outcome.REFUSED
            }

            is MemoryDraftIntake.Unavailable -> {
                log.warn(
                    "Could not file the memory proposal of conversation '{}' of agent '{}': {} — nothing was " +
                        "written and the conversation keeps its own layer for the next window",
                    sessionId,
                    domain.agentId,
                    intake.reason,
                )
                Outcome.UNAVAILABLE
            }
        }
    }

    /** The merged long-term text, or null when the model gave nothing usable. */
    private fun ask(current: StoreItem?, session: SessionLayer): String? {
        val messages = listOf(
            Msg.builder()
                .role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(systemPrompt()).build())
                .build(),
            Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(userContent(current, session)).build())
                .build(),
        )
        val merged = try {
            model.stream(messages, null, null)
                .reduce(StringBuilder()) { sb, response -> sb.append(textOf(response.content)) }
                .map { it.toString().trim() }
                .block(modelTimeout)
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            log.warn(
                "The promotion model failed for conversation '{}' of agent '{}', so nothing was proposed: {}",
                sessionId,
                domain.agentId,
                e.message,
            )
            null
        } ?: return null

        val longTerm = contentOf(current)
        if (dropsTheOwnerLayer(longTerm, merged)) {
            log.warn(
                "The merge for conversation '{}' of agent '{}' answers with {} chars where the owner's layer " +
                    "holds {}: that reads as text the model dropped rather than as a curated layer, so it was " +
                    "not proposed and this conversation keeps its own layer",
                sessionId,
                domain.agentId,
                merged.length,
                longTerm?.length ?: 0,
            )
            return null
        }
        return merged
    }

    /**
     * Whether a merge would replace the owner's text with materially less of it.
     *
     * The floor never lifts, not even for a `MEMORY.md` already over the budget the prompt states. Lifting it
     * there was the hole: a layer that drifts past the budget is exactly the one a model is likeliest to answer
     * a quarter of. The check still earns its keep now that nothing is written on the strength of it — a
     * collapsed layer is what a reviewer would approve thinking it a curation, and the conversation's own copy
     * of the dropped entries is the only thing that could still tell them so.
     *
     * Half is deliberately coarse: a faithful merge adds this conversation's entries to the owner's text
     * rather than trading a part of it away.
     */
    private fun dropsTheOwnerLayer(longTerm: String?, merged: String): Boolean {
        val current = longTerm?.takeIf { it.isNotBlank() } ?: return false
        return merged.length * 2 < current.length
    }

    private fun systemPrompt(): String = String.format(
        MemoryConsolidator.DEFAULT_CONSOLIDATION_PROMPT.trim(),
        maxMemoryTokens,
        maxMemoryTokens * CHARS_PER_TOKEN,
    ) + "\n\n" + MemoryConfigFactory.PROHIBITIONS + "\n\n" + PROMOTION_NOTES

    /** The two inputs the consolidation prompt declares: the owner's current text, then this conversation's layer. */
    private fun userContent(current: StoreItem?, session: SessionLayer): String = buildString {
        append("Current MEMORY.md:\n")
        val longTerm = contentOf(current)
        append(if (longTerm.isNullOrBlank()) "(empty)" else longTerm)
        append("\n\nNew entries to merge, all from one conversation's own memory layer (")
        append(sessionId)
        append("):\n")
        session.curated?.text?.takeIf { it.isNotBlank() }?.let { append(section(CURATED_SECTION_NAME, it)) }
        session.ledgers.forEach { append(section(it.name, it.text)) }
    }

    private fun section(name: String, text: String): String = "### $name\n${text.trim()}\n\n"

    private fun readCurated(namespace: List<String>): Draft? = store.get(namespace, MemoryFilesystemRoutes.CURATED_ITEM_KEY)?.let { Draft(contentOf(it)) }

    /**
     * This conversation's daily ledgers, oldest first, blank ones left out.
     *
     * Only the direct children of the ledger namespace count. Upstream's maintenance step moves a daily file
     * older than the retention into `memory/archive/`, and that lives in the same namespace a listing returns.
     * An archived entry is history the long-term layer already curates, and this merge would both re-propose
     * it and ask for it to be deleted.
     */
    private fun readLedgers(): List<Ledger> {
        val namespace = domain.ledgerNamespace(sessionId)
        val found = LinkedHashMap<String, Ledger>()
        var offset = 0
        while (true) {
            val page = store.search(namespace, PAGE_SIZE, offset)
            page.forEach { item ->
                val name = item.key()?.removePrefix("/") ?: return@forEach
                if (name.contains('/') || !name.endsWith(".md") || name == MemoryConsolidator.STATE_FILE) return@forEach
                contentOf(item)?.takeIf { it.isNotBlank() }?.let { found[name] = Ledger(name, it) }
            }
            if (page.size < PAGE_SIZE) break
            offset += page.size
        }
        return found.values.sortedBy { it.name }
    }

    private val store: BaseStore get() = domain.store

    companion object {

        /** How the conversation's curated draft is labelled inside the merge input. */
        private const val CURATED_SECTION_NAME = "MEMORY.md (this conversation's own curated draft)"

        /** How long one merge waits on the model before the attempt is called a failure. */
        private val DEFAULT_MODEL_TIMEOUT: Duration = Duration.ofMinutes(5)

        /** Rough characters per token — the arithmetic upstream's own prompt asks for. */
        private const val CHARS_PER_TOKEN = 4

        /** The version a long-term layer with no object yet is proposed against. */
        private const val CREATE_IF_ABSENT = 0L

        /** Page size of a bucket listing, the same one the routed filesystem pages with. */
        private const val PAGE_SIZE = 100

        /**
         * What makes a merge into a cross-session layer safe to keep: the input is one conversation, whose
         * private detail is not automatically the owner's durable knowledge.
         */
        private val PROMOTION_NOTES = """
            The entries to merge come from one conversation's own memory layer: its curated draft and its daily
            ledger files. Promote only what holds beyond this conversation — a preference, a fact about the
            user or their work, a decision still in force. Drop what only made sense while the conversation was
            running, including anything that identifies another person or a third party's private data.
            Output the complete new MEMORY.md for this owner, not a diff.
        """.trimIndent()

        /** The text blocks of one streamed model response, concatenated. */
        private fun textOf(blocks: List<ContentBlock>?): String = blocks
            ?.filterIsInstance<TextBlock>()
            ?.joinToString("") { it.text ?: "" }
            ?: ""

        /**
         * The content of a stored object, decoded the way the routed filesystem decodes it: a plain string, or
         * a list of lines joined back with newlines.
         */
        private fun contentOf(item: StoreItem?): String? = when (val raw = item?.value()?.get("content")) {
            is String -> raw
            is List<*> -> raw.joinToString("\n") { it?.toString() ?: "" }
            else -> null
        }
    }
}
