package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * One row of the memory queue, as the list shows it.
 *
 * Neither text is here on purpose. A memory candidate is a whole rewrite of one file, so its body is the
 * thing to read rather than a row to scan, and a list of two of them side by side would ask the owner to
 * compare documents in a table cell. What identifies a candidate in a work list is whose layer it changes,
 * which conversation it came out of, how much material it took, and when the merge last rewrote it.
 */
@Schema(description = "A conversation's memory merge waiting for its owner")
data class MemoryDraftResponse(
    @Schema(description = "Candidate ID", example = "41")
    val id: Long = 0,

    @Schema(description = "Agent whose long-term layer this rewrites", example = "Research")
    val agentName: String = "",

    @Schema(description = "Conversation whose own layer was merged", example = "web-0f2a…")
    val sessionId: String = "",

    @Schema(description = "PENDING / APPROVED / REJECTED", example = "PENDING")
    val status: String = "",

    @Schema(description = "Store version of the owner's layer the merge read; 0 means they had none yet", example = "3")
    val baseVersion: Long = 0,

    @Schema(description = "Length of the candidate text, so two rows of one agent can be told apart")
    val mergedChars: Int = 0,

    @Schema(description = "How many conversation-layer objects the merge took material out of")
    val sourceCount: Int = 0,

    @Schema(description = "How many daily long-term files the same approval would rewrite")
    val targetCount: Int = 0,

    @Schema(description = "First proposal time")
    val createTime: LocalDateTime? = null,

    @Schema(description = "Last proposal time; later than create means the merge rewrote the candidate since")
    val updateTime: LocalDateTime? = null,

    @Schema(description = "Owner who decided, once decided")
    val reviewedBy: String? = null,

    @Schema(description = "Decision time, once decided")
    val reviewedAt: LocalDateTime? = null,

    @Schema(description = "Why the owner rejected it")
    val rejectReason: String? = null,
)

/**
 * The candidate with both texts, so the owner can see what the merge changes rather than only what it
 * becomes.
 *
 * [baseMd] and [mergedMd] are the whole decision on the conclusion layer. [sources] is the third part — the
 * conversation files this candidate was made from and what the approval will clear out of that conversation's
 * own layer — and each entry carries the exact bytes the merge read, because an approval clears a file only
 * while it still holds them. [targets] is the fourth: the agent's own day-by-day ledger this same approval
 * rewrites, each day with the version and text it was merged against, so the owner reads every object the
 * click touches rather than only the one that gets injected into later conversations.
 *
 * [contentDigest] is computed server-side over the fields on this response, and an approval sends it back.
 * Without that round trip, "approve the text I read" would be a claim about two documents rather than a check.
 */
@Schema(description = "Full content of one memory merge, for its owner to decide on")
data class MemoryDraftDetailResponse(
    @Schema(description = "Candidate ID", example = "41")
    val id: Long = 0,

    @Schema(description = "Agent whose long-term layer this rewrites", example = "Research")
    val agentName: String = "",

    @Schema(description = "Conversation whose own layer was merged", example = "web-0f2a…")
    val sessionId: String = "",

    @Schema(description = "PENDING / APPROVED / REJECTED", example = "PENDING")
    val status: String = "",

    @Schema(description = "The complete MEMORY.md the merge produced")
    val mergedMd: String = "",

    @Schema(description = "The owner's text the merge read, null when they had none yet")
    val baseMd: String? = null,

    @Schema(description = "Store version that text was read at; 0 means the object did not exist")
    val baseVersion: Long = 0,

    @Schema(description = "Every conversation-layer file the merge read, with the bytes found there")
    val sources: List<MemoryDraftSource> = emptyList(),

    @Schema(description = "Every daily long-term file this merge produced, with the version and bytes it merged against")
    val targets: List<MemoryDraftTarget> = emptyList(),

    @Schema(description = "SHA-256 over agent, conversation, both texts, the base version, the sources and the targets as stored; required on approval")
    val contentDigest: String = "",

    @Schema(description = "First proposal time")
    val createTime: LocalDateTime? = null,

    @Schema(description = "Last proposal time")
    val updateTime: LocalDateTime? = null,

    @Schema(description = "Owner who decided, once decided")
    val reviewedBy: String? = null,

    @Schema(description = "Decision time, once decided")
    val reviewedAt: LocalDateTime? = null,

    @Schema(description = "Why the owner rejected it")
    val rejectReason: String? = null,
)
