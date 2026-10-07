package com.agnetix.harnax.harness.memory

import io.agentscope.core.message.ContentBlock
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.model.Model
import io.agentscope.harness.agent.filesystem.model.FileData
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import io.agentscope.harness.agent.memory.MemoryConsolidator
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Merges one conversation's own memory layer into its owner's long-term layer.
 *
 * The conversation layer is a buffer, not an archive: it is what the flush and the curation pass write while
 * the conversation is hot, and it has no reader outside this conversation (design 11.1). Its content only
 * becomes cross-session knowledge once it is merged into the `MEMORY.md` of the owner's bucket, which is the
 * block [LongTermMemoryContextMiddleware] injects for every later conversation.
 *
 * Two rules shape this class, both from design 11.4.
 *
 * The long-term write is a compare-and-swap, not an overwrite. Another conversation of the same owner can be
 * promoting at the same moment, and a last-write-wins merge would silently drop whatever that one added — the
 * same object this pass exists to grow. On a version mismatch this pass gives up rather than re-read and
 * retried: the material is still in this conversation's layer, so the next turn merges it against the
 * then-current long-term text instead of against a snapshot already replaced.
 *
 * The conversation layer is cleared only after a successful write. A conflict, a model that errored or said
 * nothing, and a store that refused either read or write all leave every object of the layer byte-identical,
 * because that layer holds the only copy of what has not been curated yet. Losing it to a failed merge is the
 * one way this feature could make memory shrink.
 *
 * The clear goes object by object, and only for objects that did not move while this pass was at the model.
 * The flush of a later turn appends to the same daily ledger and upstream's consolidation rewrites the same
 * draft through the mounted routes, so a file this pass read once is not necessarily the file it merged:
 * deleting one that took a write in that window would destroy entries no merge ever saw.
 *
 * A merge that shrinks the owner's layer with no size reason to is treated as the model failing the task,
 * because this pass both overwrites that text and deletes the only other copy of it.
 *
 * The merge reuses upstream's consolidation prompt verbatim: its two declared inputs are "the current
 * MEMORY.md" and "new entries to merge", and a conversation's layer is exactly the second. The prohibitions
 * come from [MemoryConfigFactory] so a merge cannot launder a secret or another tenant's data into the layer
 * every later conversation reads.
 */
class MemoryPromoter(
    private val domain: MemoryDomain,
    private val sessionId: String,
    private val model: Model,
    private val maxMemoryTokens: Int = MemoryConfigFactory.CONSOLIDATION_MAX_TOKENS,
    private val modelTimeout: Duration = DEFAULT_MODEL_TIMEOUT,
) {

    private val log = LoggerFactory.getLogger(MemoryPromoter::class.java)

    /** What one attempt did, and with it what became of the conversation layer. */
    enum class Outcome {

        /** The layer held nothing to merge, so nothing was read, written or cleared. */
        NOTHING,

        /** The merge was written and the layer cleared. */
        PROMOTED,

        /** Someone else changed the long-term layer first: nothing written, nothing cleared. */
        CONFLICT,

        /** The model failed, ran out of the time one merge gets, or answered with text that drops the owner's. */
        MODEL_FAILED,

        /** The store refused a read or the versioned write. */
        STORE_FAILED,
    }

    /**
     * What one conversation's layer holds, read once per attempt and reused for both the prompt and the clear.
     *
     * Every object carries the body this pass read, which is what lets the clear tell a file it merged from a
     * file that took a write while the model was answering.
     */
    private class SessionLayer(
        val curated: Draft?,
        val ledgers: List<Ledger>,
    ) {
        fun isEmpty(): Boolean = curated?.text.isNullOrBlank() && ledgers.isEmpty()

        /** How many objects one merge took material out of, which is how many a clear could remove. */
        fun objectCount(): Int = (if (curated?.text.isNullOrBlank()) 0 else 1) + ledgers.size
    }

    /** This conversation's un-merged draft. */
    private class Draft(val text: String?)

    /** One daily ledger of this conversation: what it is called and what this pass read in it. */
    private class Ledger(val name: String, val text: String)

    /**
     * Whether this conversation's layer holds anything a merge could take material out of — cheap, and asking
     * the same question [promoteNow] will ask two store round trips later.
     *
     * [MemoryPromotionMiddleware] asks this before it claims the throttle slot, because a claim cannot be
     * handed back: the gate has one method, and winning it writes the timestamp that closes the window. The
     * extraction that fills this layer is itself dispatched after the answer and needs a model round trip, so
     * the first turn of a conversation finds it empty as a rule. Claiming on that empty read would burn the
     * whole window on nothing — and a conversation shorter than one window, which is most of them, would then
     * never promote at all.
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
     * One promotion attempt, start to finish, called by [MemoryPromotionMiddleware] off the conversation path.
     *
     * Synchronous on purpose: the caller puts it on a blocking-friendly scheduler and every step of it is a
     * blocking store or model round trip. Splitting it into a reactive chain would move the same waits around
     * while making the one thing that matters here — which objects survive which failure — harder to read.
     */
    internal fun promoteNow(): Outcome {
        val session = try {
            SessionLayer(readCurated(domain.curatedNamespace(sessionId)), readLedgers())
        } catch (e: Exception) {
            log.warn(
                "Could not read conversation '{}' of agent '{}' for promotion, so nothing was merged: {}",
                sessionId,
                domain.agentId,
                e.message,
            )
            return Outcome.STORE_FAILED
        }
        if (session.isEmpty()) {
            log.debug("Nothing to promote out of conversation '{}' of agent '{}'", sessionId, domain.agentId)
            return Outcome.NOTHING
        }

        val longTermNamespace = domain.curatedNamespace(null)
        val current = try {
            store.get(longTermNamespace, MemoryFilesystemRoutes.CURATED_ITEM_KEY)
        } catch (e: Exception) {
            log.warn(
                "Could not read the long-term layer of agent '{}', so conversation '{}' keeps its own layer: {}",
                domain.agentId,
                sessionId,
                e.message,
            )
            return Outcome.STORE_FAILED
        }

        val merged = ask(current, session) ?: return Outcome.MODEL_FAILED
        val expectedVersion = current?.version() ?: CREATE_IF_ABSENT
        val written = try {
            store.putIfVersion(
                longTermNamespace,
                MemoryFilesystemRoutes.CURATED_ITEM_KEY,
                valueOf(merged),
                expectedVersion,
            )
        } catch (e: Exception) {
            log.warn(
                "Could not write the merged long-term layer of agent '{}', so conversation '{}' keeps its own " +
                    "layer: {}",
                domain.agentId,
                sessionId,
                e.message,
            )
            return Outcome.STORE_FAILED
        }
        if (!written) {
            log.warn(
                "The long-term layer of agent '{}' changed while conversation '{}' was being merged (expected " +
                    "version {}), so this attempt gave up with the conversation layer intact; the next turn " +
                    "merges it against the newer text",
                domain.agentId,
                sessionId,
                expectedVersion,
            )
            return Outcome.CONFLICT
        }

        val cleared = clearLayer(session)
        log.info(
            "Promoted conversation '{}' of agent '{}' into its long-term layer ({} chars, {} of {} layer " +
                "object(s) cleared)",
            sessionId,
            domain.agentId,
            merged.length,
            cleared,
            session.objectCount(),
        )
        return Outcome.PROMOTED
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
                "The promotion model failed for conversation '{}' of agent '{}', so its layer is left intact: {}",
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
                    "holds {}: that reads as text the model dropped rather than as a " +
                    "curated layer, so nothing was written and this conversation keeps its own layer",
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
     * a quarter of, and this pass overwrites that text and then deletes the conversation's copy of what went
     * into it, so a collapse reads the same as careful curation and nothing else holds the lost bytes.
     * An over-budget layer still gets curated — one halving per window converges on the budget — while a merge
     * that answers with less than half of what the owner has is refused with every byte left where it is.
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
     * An archived entry is history the long-term layer already curates, and this merge would both re-promote
     * it and delete the only copy of it.
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

    /**
     * Empties the conversation's layer: its curated draft and exactly the ledgers that were merged.
     *
     * The bucket's consolidation progress is left alone on purpose. It counts ledgers by when they were
     * written, so a progress stamp ahead of a fresh one cannot hide it, and dropping it would make the next
     * curation pass re-read ledgers this layer no longer has.
     */
    private fun clearLayer(session: SessionLayer): Int {
        var cleared = 0
        session.curated?.text?.takeIf { it.isNotBlank() }?.let {
            if (clearOne(domain.curatedNamespace(sessionId), MemoryFilesystemRoutes.CURATED_ITEM_KEY, DRAFT_NAME, it)) {
                cleared++
            }
        }
        val namespace = domain.ledgerNamespace(sessionId)
        session.ledgers.forEach { if (clearOne(namespace, "/${it.name}", it.name, it.text)) cleared++ }
        return cleared
    }

    /**
     * One object of the layer, gone only while it still holds the bytes this pass merged.
     *
     * The body is what matters, not the number the store stamps it with: an unguarded write can put any bytes
     * on any version, and this pass is about to remove the only copy of whatever is in there. Text that moved
     * means the flush of a later turn, or the curation of this conversation's own bucket, wrote through the
     * mounted routes after this pass read; those bytes reached no merge, so the object stays and the next
     * window merges them. Deleting anyway is the one way this pass loses un-curated memory. An object that
     * cannot be re-read is answered the same way, because nothing here has proved it safe to remove.
     */
    private fun clearOne(namespace: List<String>, key: String, name: String, seenText: String): Boolean {
        val current = try {
            store.get(namespace, key)
        } catch (e: Exception) {
            log.warn(
                "Could not re-check {} of conversation '{}' before clearing it, so it is left alone: {}",
                name,
                sessionId,
                e.message,
            )
            return false
        }
        if (current == null) return true
        if (contentOf(current) != seenText) {
            log.info(
                "{} of conversation '{}' holds {} chars where this pass merged {} of them, so it keeps what " +
                    "this merge never saw for the next window",
                name,
                sessionId,
                contentOf(current)?.length ?: 0,
                seenText.length,
            )
            return false
        }
        store.delete(namespace, key)
        return isGone(namespace, key, name)
    }

    /**
     * Whether the delete landed, asked of the store rather than taken on trust.
     *
     * The object store this runs against answers a failed delete by logging it and returning normally, so a
     * count of what was cleared would otherwise report memory that never left the bucket — and the difference
     * decides whether the next window merges those entries a second time into the layer every conversation reads.
     */
    private fun isGone(namespace: List<String>, key: String, name: String): Boolean = try {
        if (store.get(namespace, key) == null) {
            true
        } else {
            log.warn(
                "{} of conversation '{}' is still in the store after a delete that reported no error, so it is " +
                    "counted as kept and merged again next window",
                name,
                sessionId,
            )
            false
        }
    } catch (e: Exception) {
        log.warn(
            "Could not confirm that {} of conversation '{}' went away, so it is counted as kept: {}",
            name,
            sessionId,
            e.message,
        )
        false
    }

    private val store: BaseStore get() = domain.store

    companion object {

        /** How the conversation's curated draft is labelled inside the merge input. */
        private const val CURATED_SECTION_NAME = "MEMORY.md (this conversation's own curated draft)"

        /** How the conversation's curated draft is named in a log, where a path says nothing a reader needs. */
        private const val DRAFT_NAME = "its curated draft"

        /**
         * How long one merge waits on the model before the attempt is called a failure.
         *
         * Bounded because this runs on the scheduler the conversations share and holds one of its workers while
         * it waits: a stream that stops answering has to end on a clock rather than park a worker until the
         * process does. It is generous next to a turn's own timeout because a merge rewrites a whole curated
         * layer in one completion.
         */
        private val DEFAULT_MODEL_TIMEOUT: Duration = Duration.ofMinutes(5)

        /** Rough characters per token — the arithmetic upstream's own prompt asks for. */
        private const val CHARS_PER_TOKEN = 4

        /** The expected version of an object that must not exist yet, in the shape the store compares against. */
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

        /** The value map of a text file, in the shape the routed filesystem writes and reads back. */
        private fun valueOf(content: String): Map<String, Any> {
            val fileData = FileData.create(content)
            val value = LinkedHashMap<String, Any>()
            value["content"] = fileData.content() ?: ""
            value["encoding"] = fileData.encoding()
            fileData.createdAt()?.let { value["created_at"] = it }
            fileData.modifiedAt()?.let { value["modified_at"] = it }
            return value
        }
    }
}
