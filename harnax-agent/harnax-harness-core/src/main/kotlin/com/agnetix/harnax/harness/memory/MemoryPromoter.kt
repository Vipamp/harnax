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

        /** The model failed, or answered with nothing worth writing. */
        MODEL_FAILED,

        /** The store refused a read or the versioned write. */
        STORE_FAILED,
    }

    /** What one conversation's layer holds, read once per attempt and reused for both the prompt and the clear. */
    private class SessionLayer(
        val curated: String?,
        val ledgers: List<Pair<String, String>>,
    ) {
        fun isEmpty(): Boolean = curated.isNullOrBlank() && ledgers.isEmpty()
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
        } catch (e: RuntimeException) {
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
        } catch (e: RuntimeException) {
            log.warn(
                "Could not read the long-term layer of agent '{}', so conversation '{}' keeps its own layer: {}",
                sessionId,
                domain.agentId,
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
        } catch (e: RuntimeException) {
            log.warn(
                "Could not write the merged long-term layer of agent '{}', so conversation '{}' keeps its own " +
                    "layer: {}",
                sessionId,
                domain.agentId,
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

        clearLayer(session)
        log.info(
            "Promoted conversation '{}' of agent '{}' into its long-term layer ({} chars, {} ledger(s) cleared)",
            sessionId,
            domain.agentId,
            merged.length,
            session.ledgers.size,
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
        return try {
            model.stream(messages, null, null)
                .reduce(StringBuilder()) { sb, response -> sb.append(textOf(response.content)) }
                .map { it.toString().trim() }
                .block()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            log.warn(
                "The promotion model failed for conversation '{}' of agent '{}', so its layer is left intact: {}",
                sessionId,
                domain.agentId,
                e.message,
            )
            null
        }
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
        session.curated?.takeIf { it.isNotBlank() }?.let { append(section(CURATED_SECTION_NAME, it)) }
        session.ledgers.forEach { (name, text) -> append(section(name, text)) }
    }

    private fun section(name: String, text: String): String = "### $name\n${text.trim()}\n\n"

    private fun readCurated(namespace: List<String>): String? = contentOf(
        store.get(namespace, MemoryFilesystemRoutes.CURATED_ITEM_KEY),
    )

    /**
     * This conversation's daily ledgers, oldest first, blank ones left out.
     *
     * Only the direct children of the ledger namespace count. Upstream's maintenance step moves a daily file
     * older than the retention into `memory/archive/`, and that lives in the same namespace a listing returns.
     * An archived entry is history the long-term layer already curates, and this merge would both re-promote
     * it and delete the only copy of it.
     */
    private fun readLedgers(): List<Pair<String, String>> {
        val namespace = domain.ledgerNamespace(sessionId)
        val found = LinkedHashMap<String, String>()
        var offset = 0
        while (true) {
            val page = store.search(namespace, PAGE_SIZE, offset)
            page.forEach { item ->
                val name = item.key()?.removePrefix("/") ?: return@forEach
                if (name.contains('/') || !name.endsWith(".md") || name == MemoryConsolidator.STATE_FILE) return@forEach
                contentOf(item)?.takeIf { it.isNotBlank() }?.let { found[name] = it }
            }
            if (page.size < PAGE_SIZE) break
            offset += page.size
        }
        return found.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    /**
     * Empties the conversation's layer: its curated draft and exactly the ledgers that were merged.
     *
     * The bucket's consolidation progress is left alone on purpose. It counts ledgers by when they were
     * written, so a progress stamp ahead of a fresh one cannot hide it, and dropping it would make the next
     * curation pass re-read ledgers this layer no longer has.
     */
    private fun clearLayer(session: SessionLayer) {
        if (!session.curated.isNullOrBlank()) {
            store.delete(domain.curatedNamespace(sessionId), MemoryFilesystemRoutes.CURATED_ITEM_KEY)
        }
        if (session.ledgers.isNotEmpty()) {
            val namespace = domain.ledgerNamespace(sessionId)
            session.ledgers.forEach { (name, _) -> store.delete(namespace, "/$name") }
        }
    }

    private val store: BaseStore get() = domain.store

    companion object {

        /** How the conversation's curated draft is labelled inside the merge input. */
        private const val CURATED_SECTION_NAME = "MEMORY.md (this conversation's own curated draft)"

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
