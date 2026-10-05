package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * What a review action actually did.
 *
 * A refusal travels in the payload, not in the envelope code, and that is a deliberate break from the rest
 * of this API. The three refusals a reviewer can hit are not the same event: `DRAFT_CHANGED` needs the new
 * digest so the screen can force a re-read, `ALREADY_REVIEWED` needs who decided and why, `NAME_TAKEN` needs
 * to open a replace-or-rename choice. `ResultVo`'s error branch carries a message and no data, and a page
 * that has to re-fetch to recover information it was just refusing to act on is a page with a race in it.
 * So the outcome is a field, and the message the operator reads is composed from it.
 *
 * [outcome] is the stable contract; the wording in [reason] is not.
 */
@Schema(description = "Outcome of a draft approval or rejection")
data class SkillDraftDecisionResponse(
    @Schema(description = "PROMOTED / REJECTED / DRAFT_CHANGED / ALREADY_REVIEWED / NAME_TAKEN", example = "PROMOTED")
    val outcome: String = OUTCOME_PROMOTED,

    @Schema(description = "Skill row the draft was promoted into, null for every refusal and for a rejection")
    val skillId: Long? = null,

    @Schema(description = "Status stored on that row: 1 enabled, 0 held back by the content scan", example = "1")
    val skillStatus: Int? = null,

    @Schema(description = "Name the skill was stored under; differs from the proposal after a rename", example = "invoice-pdf-fill-v2")
    val promotedName: String? = null,

    @Schema(description = "harnax content-scan hits behind skillStatus = 0, one line each")
    val findings: List<String> = emptyList(),

    @Schema(description = "Sentence describing a refusal or a rejection; not a contract, localize on outcome")
    val reason: String? = null,

    @Schema(description = "Digest the row holds now, present on DRAFT_CHANGED")
    val currentDigest: String? = null,

    @Schema(description = "Reviewer who got there first, present on ALREADY_REVIEWED")
    val reviewedBy: String? = null,

    @Schema(description = "When that decision was made, present on ALREADY_REVIEWED")
    val reviewedAt: LocalDateTime? = null,

    @Schema(description = "Reject reason of the decided draft, present on ALREADY_REVIEWED after a rejection")
    val rejectReason: String? = null,
) {

    companion object {
        /** The draft became a skill row. Check [skillStatus] before saying it is live. */
        const val OUTCOME_PROMOTED = "PROMOTED"

        /** The proposal is closed with a reason; nothing was written to the skill table. */
        const val OUTCOME_REJECTED = "REJECTED"

        /** The agent patched the draft after the reviewer loaded it; nothing was written. */
        const val OUTCOME_DRAFT_CHANGED = "DRAFT_CHANGED"

        /** Another reviewer decided this one first; the draft is no longer PENDING. */
        const val OUTCOME_ALREADY_REVIEWED = "ALREADY_REVIEWED"

        /** The landing repository already holds that name and the caller has not chosen replace or rename. */
        const val OUTCOME_NAME_TAKEN = "NAME_TAKEN"

        fun promoted(
            skillId: Long,
            skillStatus: Int,
            promotedName: String,
            findings: List<String>,
        ) = SkillDraftDecisionResponse(
            outcome = OUTCOME_PROMOTED,
            skillId = skillId,
            skillStatus = skillStatus,
            promotedName = promotedName,
            findings = findings,
            reason = if (findings.isEmpty()) {
                "Promoted and enabled as '$promotedName'"
            } else {
                "Promoted as '$promotedName' but held disabled: the content scan matched ${findings.size} rule(s)"
            },
        )

        fun rejected(reason: String) = SkillDraftDecisionResponse(outcome = OUTCOME_REJECTED, reason = reason)

        fun draftChanged(currentDigest: String) = SkillDraftDecisionResponse(
            outcome = OUTCOME_DRAFT_CHANGED,
            reason = "The draft changed after you loaded it; re-read it and approve the content you actually saw",
            currentDigest = currentDigest,
        )

        fun alreadyReviewed(
            name: String,
            reviewedBy: String?,
            reviewedAt: LocalDateTime?,
            rejectReason: String?,
        ) = SkillDraftDecisionResponse(
            outcome = OUTCOME_ALREADY_REVIEWED,
            reason = "'$name' was already decided" + (reviewedBy?.let { " by $it" } ?: "") +
                (rejectReason?.let { ": $it" } ?: ""),
            reviewedBy = reviewedBy,
            reviewedAt = reviewedAt,
            rejectReason = rejectReason,
        )

        fun nameTaken(
            name: String,
            existingSkillId: Long?,
        ) = SkillDraftDecisionResponse(
            outcome = OUTCOME_NAME_TAKEN,
            reason = "A skill named '$name' already exists" +
                (existingSkillId?.let { " (id $it)" } ?: "") +
                "; choose replace or promote under a new name",
            skillId = existingSkillId,
        )
    }
}
