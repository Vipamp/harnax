package com.agnetix.harnax.harness.memory

import com.agnetix.harnax.agent.adaptor.MemoryDraftAdaptor
import com.agnetix.harnax.agent.adaptor.MemoryDraftIntake
import com.agnetix.harnax.agent.adaptor.MemoryDraftProposal
import com.agnetix.harnax.agent.adaptor.MemoryDraftSource
import com.agnetix.harnax.agent.adaptor.MemoryDraftTarget
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
 *
 * The long-term layer is not one object. Beside `MEMORY.md` the agent keeps a ledger of its own with one file
 * per day, and a conversation's day merges into the file of the same date — the agent's own day and this
 * conversation's entries for it, and nothing else. Each of those days therefore gets its own model call, its
 * own precondition in [MemoryDraftProposal.targets] and its own shrink guard, because a sibling conversation's
 * approval may have rewritten that day while this pass sat at the model. One attempt covers a handful of the
 * oldest days and leaves the rest for its next window, since it has to finish inside the throttle slot it
 * claimed and that slot is stamped when the claim is taken, not when the work ends.
 */
class MemoryPromoter(
    private val domain: MemoryDomain,
    private val sessionId: String,
    private val model: Model,
    private val draftAdaptor: MemoryDraftAdaptor,
    private val maxMemoryTokens: Int = MemoryConfigFactory.CONSOLIDATION_MAX_TOKENS,
    private val modelTimeout: Duration = DEFAULT_MODEL_TIMEOUT,
    private val totalModelBudget: Duration = TOTAL_MODEL_BUDGET,
) {

    private val log = LoggerFactory.getLogger(MemoryPromoter::class.java)

    /** What one attempt did, and with it whether a reviewer has anything to decide. */
    enum class Outcome {

        /** The layer held nothing to merge, so nothing was read and no model call was spent. */
        NOTHING,

        /** The merge was filed; a person now decides it. */
        QUEUED,

        /** A merge failed, the attempt ran out of model time, or an answer drops the text it was given. */
        MODEL_FAILED,

        /** The store refused one of the reads this candidate is built from — the owner's layer or one of its days. */
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
         * The part of this layer one attempt takes: the curated draft and the oldest days up to the cap.
         *
         * The cap is arithmetic, not taste — a day costs a model call and the attempt has to end inside the
         * throttle window it claimed when it started. Taking the oldest first is what turns the days over the
         * cap into a delay rather than a queue that never drains: a deferred day leads the next window.
         */
        fun inScope(): SessionLayer = SessionLayer(curated, ledgers.take(MAX_DAILY_TARGETS))

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
     * The model time one attempt has left, handed out a call at a time.
     *
     * An attempt now makes several merges, and the throttle slot it runs in was stamped when it was claimed
     * rather than when the work finishes: eight calls that each wait out their own timeout would run past the
     * window and let the same conversation start a second attempt over the top of the first. Each call still
     * gets its own timeout — [nextCall] never lengthens one, it only cuts the last one short — so a day whose
     * model stops answering costs that day's merge and not the whole budget.
     */
    internal class ModelBudget(
        total: Duration,
        private val perCall: Duration,
        private val nanoClock: () -> Long = System::nanoTime,
    ) {
        private val startedAt = nanoClock()
        private val totalNanos = total.toNanos()
        private val perCallNanos = perCall.toNanos()

        /** How long the next call may take, or null once this attempt has spent its budget. */
        fun nextCall(): Duration? {
            val remaining = totalNanos - (nanoClock() - startedAt)
            if (remaining <= 0L) return null
            return Duration.ofNanos(minOf(remaining, perCallNanos))
        }
    }

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
     *
     * The candidate is filed whole or not at all. A partial one would ask a reviewer to approve a decision
     * whose gaps they cannot see, and an approval clears the conversation's objects on the strength of what a
     * merge covered, so a day that failed to merge would leave material behind that the candidate already
     * claims to hold.
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
        val inScope = session.inScope()

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

        val budget = ModelBudget(totalModelBudget, modelTimeout)
        val conclusion = ask(longTerm, inScope, budget) ?: return Outcome.MODEL_FAILED

        // Read every day before merging any of them: an object the bucket cannot answer for is not an absent
        // one, and proposing a create over a day that holds text would hand an approval an overwrite of bytes
        // no merge ever read.
        val days = try {
            inScope.ledgers.map { it to store.get(domain.ledgerNamespace(null), "/${it.name}") }
        } catch (e: Exception) {
            log.warn(
                "Could not read the agent's own days of long-term memory, so conversation '{}' of agent '{}' " +
                    "proposed nothing: {}",
                domain.agentId,
                sessionId,
                e.message,
            )
            return Outcome.STORE_FAILED
        }
        val targets = mergeDays(days, budget) ?: return Outcome.MODEL_FAILED

        return when (
            val intake = draftAdaptor.propose(
                MemoryDraftProposal(
                    sessionId = sessionId,
                    agentName = domain.agentId,
                    mergedMarkdown = conclusion,
                    baseMarkdown = contentOf(longTerm)?.takeIf { it.isNotBlank() },
                    baseVersion = longTerm?.version() ?: CREATE_IF_ABSENT,
                    sources = inScope.sources(),
                    targets = targets,
                ),
            )
        ) {
            is MemoryDraftIntake.Queued -> {
                log.info(
                    "Proposed promoting conversation '{}' of agent '{}' into its long-term layer ({} conclusion " +
                        "chars, {} day(s), {} layer object(s) merged, draft {} awaits review)",
                    sessionId,
                    domain.agentId,
                    conclusion.length,
                    targets.size,
                    inScope.objectCount(),
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

    /** The merged conclusion layer, or null when the model gave nothing usable for it. */
    private fun ask(
        current: StoreItem?,
        session: SessionLayer,
        budget: ModelBudget,
    ): String? {
        val timeout = budget.nextCall()
        if (timeout == null) {
            log.warn(
                "The promotion of conversation '{}' of agent '{}' had no model time left for the conclusion " +
                    "merge, so nothing was proposed",
                sessionId,
                domain.agentId,
            )
            return null
        }
        val merged = callModel(systemPrompt(), userContent(current, session), timeout) ?: return null

        val longTerm = contentOf(current)
        if (dropsMostOfWhatItRead(longTerm, merged)) {
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
     * One merge per day this attempt took, or null as soon as one of them gives nothing usable.
     *
     * Each day merges on its own because each day is its own object with its own precondition: the agent's
     * file for a date is not `MEMORY.md`, and a sibling conversation approved since this pass read it rewrote
     * exactly that one. A day that cannot be merged takes the candidate down with it (D7), and the version
     * recorded is the one this pass read, so an approval that finds the day moved refuses it by name.
     */
    private fun mergeDays(
        days: List<Pair<Ledger, StoreItem?>>,
        budget: ModelBudget,
    ): List<MemoryDraftTarget>? {
        val targets = ArrayList<MemoryDraftTarget>(days.size)
        for ((ledger, existing) in days) {
            val before = contentOf(existing)?.takeIf { it.isNotBlank() }
            val timeout = budget.nextCall()
            if (timeout == null) {
                log.warn(
                    "The promotion of conversation '{}' of agent '{}' spent its model budget before the day '{}' " +
                        "was merged, so nothing was proposed",
                    sessionId,
                    domain.agentId,
                    ledger.name,
                )
                return null
            }
            val merged = callModel(daySystemPrompt(), dayUserContent(ledger, before), timeout) ?: return null
            if (dropsMostOfWhatItRead(before, merged)) {
                log.warn(
                    "The merge for the day '{}' of conversation '{}' answers with {} chars where that day of the " +
                        "agent's own ledger holds {}: that reads as a day dropped rather than a day merged, so " +
                        "nothing was proposed",
                    ledger.name,
                    sessionId,
                    merged.length,
                    before?.length ?: 0,
                )
                return null
            }
            targets.add(
                MemoryDraftTarget(
                    path = MemoryFilesystemRoutes.MEMORY_DIR_ROUTE + ledger.name,
                    expectedVersion = existing?.version() ?: CREATE_IF_ABSENT,
                    baseText = before,
                    mergedText = merged,
                ),
            )
        }
        return targets
    }

    /** One merge call, answered or null: a model that fails, runs out of its time or returns nothing usable. */
    private fun callModel(
        system: String,
        user: String,
        timeout: Duration,
    ): String? = try {
        model.stream(
            listOf(
                Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(user).build()).build(),
            ),
            null,
            null,
        )
            .reduce(StringBuilder()) { sb, response -> sb.append(textOf(response.content)) }
            .map { it.toString().trim() }
            .block(timeout)
            ?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        log.warn(
            "The promotion model failed for conversation '{}' of agent '{}', so nothing was proposed: {}",
            sessionId,
            domain.agentId,
            e.message,
        )
        null
    }

    /**
     * Whether a merge would replace the text it was given with materially less of it.
     *
     * One guard, applied per object the candidate rewrites: the owner's `MEMORY.md` and each day of their
     * ledger on its own bytes. The floor never lifts, not even for a layer already over the budget the prompt
     * states. Lifting it there was the hole: an oversized object is exactly the one a model is likeliest to
     * answer a quarter of. The check still earns its keep now that nothing is written on the strength of it — a
     * collapsed object is what a reviewer would approve thinking it a curation, and the conversation's own copy
     * of the dropped entries is the only thing that could still tell them so.
     *
     * Half is deliberately coarse: a faithful merge adds this conversation's entries to the text it read
     * rather than trading a part of it away.
     */
    private fun dropsMostOfWhatItRead(
        before: String?,
        merged: String,
    ): Boolean {
        val current = before?.takeIf { it.isNotBlank() } ?: return false
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

    /**
     * The day merge's own prompt.
     *
     * Upstream's consolidation prompt is not reusable here: it declares its output as "the complete new
     * MEMORY.md", and handed a day file it would have the model curate several days into one conclusion. The
     * two prohibitions are the same ones the conclusion merge carries, for the same reason — an approved day
     * becomes durable text about this owner just like an approved line of `MEMORY.md` does. The token budget is
     * left out on purpose: 4000 tokens is the ceiling of the conclusion layer, and giving a day the same one
     * asks the merge to trim today's entries until they fit.
     */
    private fun daySystemPrompt(): String = DAY_MERGE_TASK + "\n\n" + MemoryConfigFactory.PROHIBITIONS + "\n\n" + DAY_MERGE_OUTPUT

    /** The two inputs a day merge declares: the agent's own file for that day, then this conversation's. */
    private fun dayUserContent(
        ledger: Ledger,
        existing: String?,
    ): String = buildString {
        append("The agent's own long-term file for ")
        append(ledger.name.removeSuffix(".md"))
        append(":\n")
        append(existing ?: "(empty)")
        append("\n\nNew entries for the same day, from one conversation's own memory layer (")
        append(sessionId)
        append("):\n")
        append(section(ledger.name, ledger.text))
    }

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

        /**
         * How long one attempt may sit at the model in total, across every merge it makes.
         *
         * Shorter than the throttle window on purpose: the promotion gate stamps its claim when the attempt
         * takes it, so an attempt that outlives its own window lets this conversation start a second one over
         * the top of it. That wastes a full round of merges and hands the queue two candidates for one layer.
         */
        private val TOTAL_MODEL_BUDGET: Duration = Duration.ofMinutes(25)

        /**
         * How many days one attempt merges.
         *
         * A bound on how many merges an attempt starts, not on how long it may take — eight calls that each
         * waited out [DEFAULT_MODEL_TIMEOUT] would overrun the window even at this cap, which is why
         * [TOTAL_MODEL_BUDGET] cuts the later ones short. Days past the cap wait for the next window, oldest
         * first, so none of them is ever skipped.
         */
        private const val MAX_DAILY_TARGETS = 7

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

        /** What a day merge is for: one date, two sources, one day's record out of them. */
        private val DAY_MERGE_TASK = """
            Merge one day of an owner's long-term memory ledger. The inputs are the owner's own file for that
            date and one conversation's entries for the same date. Keep the facts that hold beyond the
            conversation — what the owner did, decided or stated that day — drop what only made sense while the
            conversation was running, and fold away duplicates. Write nothing about another day, and do not
            summarise the owner's memory as a whole.
        """.trimIndent()

        /** The output contract a day file needs, which is not the one the conclusion layer's prompt states. */
        private val DAY_MERGE_OUTPUT = """
            Output the complete new text of that one day's file: the owner's entries for it plus the ones worth
            keeping from the conversation. Not a diff, not a summary, and no markdown fences around it.
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
