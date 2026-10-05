package com.agnetix.harnax.admin.skill

import com.agnetix.harnax.entity.SkillDraft
import tools.jackson.core.type.TypeReference
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest

/**
 * The one place that knows how a `skill_draft` row encodes what a reviewer reads.
 *
 * Three things live here because two callers must not be able to disagree about them. The queue screen and
 * the approve endpoint both show or check the same content, and an approval that verified a digest computed
 * one way against a screen that displayed another would let a reviewer approve bytes different from the ones
 * stored. So the canonical form of [SkillDraft.resources], the per-script preview column, the scan-findings
 * column and the content digest are derived here and nowhere else.
 *
 * The script preview keeps upstream's own shape — first 40 lines, line count, sha256 of the whole file —
 * because the runtime that produced the draft hashes the same way (`SkillPromoter.buildScriptPreview`). A
 * reviewer comparing the digest in this table with the one the sandbox reported then sees one number, not
 * two that differ by a newline.
 */
object SkillDraftCodec {

    // Kotlin-aware on purpose: scriptPreviewsJson writes a data class and a bare ObjectMapper reads it back
    // with every field at its default, which shows a reviewer an empty script table rather than an error.
    private val objectMapper = jacksonObjectMapper()
    private const val HEAD_LINES = 40

    /** Storage format of `resources`: sorted so two submits of the same files store the same bytes. */
    fun resourcesJson(resources: Map<String, String>): String = objectMapper.writeValueAsString(resources.toSortedMap())

    /** What a submit carried back to a reviewer-visible list; a corrupt column reads as no files. */
    fun resourcesOf(draft: SkillDraft): Map<String, String> = draft.resources
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching { objectMapper.readValue(it, object : TypeReference<Map<String, String>>() {}) }
                .getOrNull()
        }
        .orEmpty()

    /**
     * `script_previews`: one entry per `scripts/` file, in path order.
     *
     * Computed here rather than taken from the runtime because the reviewer approves what this table holds:
     * a hash the caller supplied would describe the bytes it sent, which are not necessarily the bytes stored.
     */
    fun scriptPreviewsJson(resources: Map<String, String>): String {
        val previews = resources.toSortedMap()
            .filterKeys { it.startsWith(SCRIPT_PREFIX) }
            .map { (path, body) ->
                ScriptPreview(
                    relPath = path,
                    headPreview = headOf(body),
                    // Kotlin keeps the trailing empty piece, which is upstream's `split("\n", -1)` count
                    totalLines = body.split("\n").size,
                    sha256 = sha256Hex(body),
                )
            }
        return objectMapper.writeValueAsString(previews)
    }

    fun previewsOf(draft: SkillDraft): List<ScriptPreview> = draft.scriptPreviews
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching { objectMapper.readValue(it, object : TypeReference<List<ScriptPreview>>() {}) }
                .getOrNull()
        }
        .orEmpty()

    /** `scan_findings`: what the sandbox reported, one line each; null when the proposal carried none. */
    fun findingsJson(findings: List<String>?): String? = findings
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() }
        ?.let { objectMapper.writeValueAsString(it) }

    /** Findings are display text only, so a corrupt column costs the note, never the draft. */
    fun findingsOf(draft: SkillDraft): List<String> = draft.scanFindings
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching { objectMapper.readValue(it, object : TypeReference<List<String>>() {}) }
                .getOrNull()
        }
        .orEmpty()

    /**
     * Digest of everything a reviewer decides on: the two descriptive columns, the body, and the support
     * files as stored.
     *
     * Each field is length-prefixed, so content cannot be re-split across a boundary to reproduce a digest
     * that covered different bytes. Scripts are covered through [SkillDraft.resources], which holds their
     * full text; the preview column is derived from it and adds nothing to decide on.
     */
    fun contentDigest(draft: SkillDraft): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in listOf(draft.name, draft.description.orEmpty(), draft.skillmd, draft.resources.orEmpty())) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}\n".toByteArray(Charsets.UTF_8))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun headOf(body: String): String = body.split("\n").take(HEAD_LINES).joinToString("\n")

    private fun sha256Hex(body: String): String = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** relPath / headPreview / totalLines / sha256, the four columns upstream hands a promotion gate. */
    data class ScriptPreview(
        val relPath: String = "",
        val headPreview: String = "",
        val totalLines: Int = 0,
        val sha256: String = "",
    )

    private const val SCRIPT_PREFIX = "scripts/"
}
