package com.agnetix.harnax.admin.util

import com.agnetix.harnax.admin.dto.MemoryDraftSource
import com.agnetix.harnax.admin.dto.MemoryDraftTarget
import com.agnetix.harnax.entity.MemoryDraft
import tools.jackson.core.type.TypeReference
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest

/**
 * The one place that knows how a `memory_draft` row encodes what its owner reads.
 *
 * Three callers must not be able to disagree about it. Intake writes the columns, the queue screen computes
 * the digest it displays, and the approval recomputes the digest it checks — so the canonical form of
 * [MemoryDraft.sources] and [MemoryDraft.targets] and the digest over every column a decision covers live
 * here and nowhere else. A digest computed one way and verified against another would let an owner approve
 * bytes different from the ones stored, which is the exact failure the digest exists to prevent.
 *
 * Both lists sort by path, so two proposals of one conversation's layer store the same bytes: the order the
 * merge read them in carries nothing an owner decides on.
 */
object MemoryDraftCodec {

    // Kotlin-aware on purpose: a bare ObjectMapper reads a data class with a null field as no default, which
    // would show an owner an empty source list rather than the files the merge read.
    private val objectMapper = jacksonObjectMapper()

    /** Storage format of `sources`: sorted by path and written canonically. */
    fun sourcesJson(sources: List<MemoryDraftSource>): String = objectMapper.writeValueAsString(sources.sortedBy { it.path.orEmpty() })

    /** What a candidate was made from; a corrupt column reads as no sources rather than losing the row. */
    fun sourcesOf(draft: MemoryDraft): List<MemoryDraftSource> = draft.sources
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching { objectMapper.readValue(it, object : TypeReference<List<MemoryDraftSource>>() {}) }
                .getOrNull()
        }
        .orEmpty()

    /** Storage format of `targets`: sorted by path and written canonically. */
    fun targetsJson(targets: List<MemoryDraftTarget>): String = objectMapper.writeValueAsString(targets.sortedBy { it.path.orEmpty() })

    /** What a candidate would write in the agent's own ledger; a corrupt column reads as no targets rather than losing the row. */
    fun targetsOf(draft: MemoryDraft): List<MemoryDraftTarget> = draft.targets
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching { objectMapper.readValue(it, object : TypeReference<List<MemoryDraftTarget>>() {}) }
                .getOrNull()
        }
        .orEmpty()

    /**
     * Digest of everything an approval acts on: which agent's layer, which conversation, the candidate text,
     * the base it was merged against, the store version that base was read at, the source files, and the
     * daily files the same click writes.
     *
     * Each field is length-prefixed, so text cannot be re-split across a boundary to reproduce a digest that
     * covered different bytes. [MemoryDraft.baseVersion] is in there because the approval applies the text to
     * those bytes or refuses: a candidate silently moved to a newer base is a different decision, and an owner
     * who read one should not be able to sign the other. [MemoryDraft.targets] is in there for the same
     * reason one row further along — those texts are written by this same approval, so a candidate whose
     * ledger half was swapped after the page loaded is not the decision the owner read.
     */
    fun contentDigest(draft: MemoryDraft): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in listOf(
            draft.agentName,
            draft.sessionId,
            draft.mergedMd,
            draft.baseMd.orEmpty(),
            draft.baseVersion.toString(),
            draft.sources.orEmpty(),
            draft.targets.orEmpty(),
        )) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}\n".toByteArray(Charsets.UTF_8))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
