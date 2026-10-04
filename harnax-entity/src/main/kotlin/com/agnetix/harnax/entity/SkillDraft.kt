package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * A skill an agent proposed and a human has not decided on yet.
 *
 * A draft lives here and nowhere else until it is approved: `skill.status` stays a two-value column, so
 * stuffing "pending review" into it as a third value would turn every `status == 0` check on the delivery
 * path into a place where unpublished content leaks. Columns mirror what the upstream
 * `SkillCandidate` hands the promotion gate, including the per-script hashes a reviewer needs before
 * approving a skill whose scripts the sandbox will execute.
 *
 * There is no unique key on purpose. An agent may propose the same name twice, in one session or several;
 * the collision is resolved when a reviewer approves one of them, not by dropping a proposal on insert.
 */
@Schema(description = "Skill proposed by an agent and awaiting human review")
class SkillDraft : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /** Submitted, not yet decided. */
        const val STATUS_PENDING = "PENDING"

        /** Approved: promoted into `skill`. */
        const val STATUS_APPROVED = "APPROVED"

        /** Rejected by a reviewer, with [rejectReason]. */
        const val STATUS_REJECTED = "REJECTED"

        /**
         * The only status a submit may write. A decided row is a decision record, so a later patch from the
         * same agent must not silently reopen it.
         */
        val OPEN_STATUSES = setOf(STATUS_PENDING)
    }

    @Schema(description = "Draft ID")
    var id: Long = 0

    @Schema(description = "Tenant resolved server-side from the session, never taken from the request body")
    var tenantId: Long = 1

    @Schema(description = "Proposed skill name")
    var name: String = ""

    @Schema(description = "Proposed description")
    var description: String? = null

    @Schema(description = "Proposed SKILL.md body")
    var skillmd: String = ""

    @Schema(description = "path -> content JSON, the same shape as skill.resources")
    var resources: String? = null

    @Schema(description = "Per-script relPath / headPreview / totalLines / sha256, from SkillCandidate.scriptFiles")
    var scriptPreviews: String? = null

    @Schema(description = "Upstream scan verdict: SAFE / CAUTION / DANGEROUS")
    var scanVerdict: String? = null

    @Schema(description = "Upstream scan findings as JSON; rescanned and overwritten at promotion")
    var scanFindings: String? = null

    @Schema(description = "Session the agent proposed the skill in")
    var sourceSessionId: String = ""

    @Schema(description = "Agent that proposed it, resolved from the session")
    var agentId: Long? = null

    @Schema(description = "PENDING / APPROVED / REJECTED / EXPIRED")
    var status: String = STATUS_PENDING

    @Schema(description = "Reviewer username, once decided")
    var reviewedBy: String? = null

    @Schema(description = "Review time, once decided")
    var reviewedAt: LocalDateTime? = null

    @Schema(description = "Why a reviewer rejected it")
    var rejectReason: String? = null

    @Schema(description = "Submission time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Last patch time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
