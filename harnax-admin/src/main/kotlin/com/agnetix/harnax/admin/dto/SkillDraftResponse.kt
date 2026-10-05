package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.admin.skill.SkillDraftCodec
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * One row of the review queue, as the list shows it.
 *
 * The body and the support files are deliberately absent: a queue is a work list, and the columns that
 * decide a row's place in it are the two timestamps. [updateTime] is the one a reviewer must read before
 * approving — it moved means the agent patched the draft since it was first proposed, which is what the
 * content digest check at approval exists to catch.
 */
@Schema(description = "A skill an agent proposed and a human has not decided on yet")
data class SkillDraftResponse(
    @Schema(description = "Draft ID", example = "17")
    val id: Long = 0,

    @Schema(description = "Proposed skill name", example = "invoice-pdf-fill")
    val name: String = "",

    @Schema(description = "Proposed description")
    val description: String? = null,

    @Schema(description = "PENDING / APPROVED / REJECTED / EXPIRED", example = "PENDING")
    val status: String = "",

    @Schema(description = "Upstream security scan verdict", example = "CAUTION")
    val scanVerdict: String? = null,

    @Schema(description = "Findings the upstream sandbox reported with the proposal")
    val upstreamFindingCount: Int = 0,

    @Schema(description = "Session the agent proposed the skill in")
    val sourceSessionId: String = "",

    @Schema(description = "Agent that proposed it, resolved from the session")
    val agentId: Long? = null,

    @Schema(description = "First proposal time")
    val createTime: LocalDateTime? = null,

    @Schema(description = "Last patch time; later than create means the content a reviewer saw may have moved")
    val updateTime: LocalDateTime? = null,

    @Schema(description = "Reviewer username, once decided")
    val reviewedBy: String? = null,

    @Schema(description = "Decision time, once decided")
    val reviewedAt: LocalDateTime? = null,

    @Schema(description = "Why a reviewer rejected it")
    val rejectReason: String? = null,
)

/**
 * The queue row with everything a decision needs, plus the trail of how it got here.
 *
 * [contentDigest] is computed by the server over exactly the fields on this response, and an approval
 * sends it back. Without that round trip, "approve what I am looking at" would be a claim about two
 * independently formatted documents rather than a check.
 *
 * [localFindings] are harnax's own rules run over the stored body at read time. The two scans are shown
 * side by side because they decide different things: [scanFindings] is what the sandbox reported when the
 * proposal arrived and is display only, while [localFindings] is what will decide whether an approved skill
 * lands enabled. Computed rather than stored so it always describes the bytes on this row — a stored copy
 * would go stale the moment the agent patched.
 */
@Schema(description = "Full content of one proposed skill, for a human to decide on")
data class SkillDraftDetailResponse(
    @Schema(description = "Draft ID", example = "17")
    val id: Long = 0,

    @Schema(description = "Proposed skill name", example = "invoice-pdf-fill")
    val name: String = "",

    @Schema(description = "Proposed description")
    val description: String? = null,

    @Schema(description = "PENDING / APPROVED / REJECTED / EXPIRED", example = "PENDING")
    val status: String = "",

    @Schema(description = "Full SKILL.md body as stored")
    val skillmd: String = "",

    @Schema(description = "Support files as path -> content")
    val resources: Map<String, String> = emptyMap(),

    @Schema(description = "Per-script head, line count and sha256 of the stored bytes")
    val scripts: List<SkillDraftCodec.ScriptPreview> = emptyList(),

    @Schema(description = "Upstream security scan verdict", example = "CAUTION")
    val scanVerdict: String? = null,

    @Schema(description = "Upstream scan findings, one line each; display only")
    val scanFindings: List<String> = emptyList(),

    @Schema(description = "Harnax content-scan hits; non-empty means an approval stores the skill disabled")
    val localFindings: List<String> = emptyList(),

    @Schema(description = "SHA-256 over name, description, body and files as stored; required on approval")
    val contentDigest: String = "",

    @Schema(description = "Session the agent proposed the skill in")
    val sourceSessionId: String = "",

    @Schema(description = "Agent that proposed it, resolved from the session")
    val agentId: Long? = null,

    @Schema(description = "First proposal time")
    val createTime: LocalDateTime? = null,

    @Schema(description = "Last patch time")
    val updateTime: LocalDateTime? = null,

    @Schema(description = "Reviewer username, once decided")
    val reviewedBy: String? = null,

    @Schema(description = "Decision time, once decided")
    val reviewedAt: LocalDateTime? = null,

    @Schema(description = "Why a reviewer rejected it")
    val rejectReason: String? = null,

    @Schema(description = "Proposals and decisions on this draft, newest first")
    val history: List<ReviewHistoryItem> = emptyList(),
)

/** One entry of a draft's trail; the tenant and subject columns that identify the row to the database stay server-side. */
@Schema(description = "One recorded state change of a draft")
data class ReviewHistoryItem(
    @Schema(description = "PROPOSE / APPROVE / REJECT", example = "PROPOSE")
    val action: String = "",

    @Schema(description = "Username, or the sentinel agent / system", example = "agent")
    val actor: String = "",

    @Schema(description = "JSON note stored with the action")
    val detail: String? = null,

    @Schema(description = "When it happened")
    val createTime: LocalDateTime? = null,
)
