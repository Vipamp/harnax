package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * What a memory decision actually did.
 *
 * A refusal travels in the payload rather than in the envelope code, the same deliberate break from the rest
 * of this API a skill review makes. The refusals here are not the same event and each needs different
 * information to be actionable: `DRAFT_CHANGED` needs the new digest so the screen can force a re-read,
 * `ALREADY_REVIEWED` needs who decided and why, `STALE_BASE` needs the version the owner's layer is at now —
 * without it the owner cannot tell whether the layer moved because they approved another candidate or because
 * a second conversation merged first. `ResultVo`'s error branch carries a message and no data, and a screen
 * that has to re-fetch to recover what it was just refusing to do is a screen with a race in it.
 *
 * [outcome] is the stable contract; the wording in [reason] is not.
 */
@Schema(description = "Outcome of a memory merge approval or rejection")
data class MemoryDraftDecisionResponse(
    @Schema(description = "APPROVED / REJECTED / DRAFT_CHANGED / ALREADY_REVIEWED / STALE_BASE", example = "APPROVED")
    val outcome: String = OUTCOME_APPROVED,

    @Schema(description = "Version the owner's long-term layer is at after an approval", example = "4")
    val longTermVersion: Long? = null,

    @Schema(description = "Conversation-layer files this approval cleared", example = "2")
    val clearedSources: Int = 0,

    @Schema(description = "Files it left because their bytes moved after the merge read them; they re-enter the next candidate", example = "1")
    val keptSources: Int = 0,

    @Schema(description = "Files already gone, so nothing was cleared for them", example = "0")
    val absentSources: Int = 0,

    @Schema(description = "Sentence describing a refusal or a rejection; not a contract, localize on outcome")
    val reason: String? = null,

    @Schema(description = "Digest the row holds now, present on DRAFT_CHANGED")
    val currentDigest: String? = null,

    @Schema(description = "Version the owner's layer is at now, present on STALE_BASE", example = "5")
    val currentBaseVersion: Long? = null,

    @Schema(description = "Owner who got there first, present on ALREADY_REVIEWED")
    val reviewedBy: String? = null,

    @Schema(description = "When that decision was made, present on ALREADY_REVIEWED")
    val reviewedAt: LocalDateTime? = null,

    @Schema(description = "Reject reason of the decided candidate, present on ALREADY_REVIEWED after a rejection")
    val rejectReason: String? = null,
) {

    companion object {
        /** The owner's long-term layer now holds the candidate, and the sources it merged out of are cleared. */
        const val OUTCOME_APPROVED = "APPROVED"

        /** The candidate is closed with a reason; the long-term layer and the conversation's layer are untouched. */
        const val OUTCOME_REJECTED = "REJECTED"

        /** The conversation re-merged after the owner loaded the candidate; nothing was written. */
        const val OUTCOME_DRAFT_CHANGED = "DRAFT_CHANGED"

        /** The owner, or a second tab of theirs, decided this one first; the candidate is no longer PENDING. */
        const val OUTCOME_ALREADY_REVIEWED = "ALREADY_REVIEWED"

        /** The layer moved since the merge read it, so applying this text would overwrite a merge nobody read. */
        const val OUTCOME_STALE_BASE = "STALE_BASE"

        fun approved(
            longTermVersion: Long,
            cleared: Int,
            kept: Int,
            absent: Int,
        ) = MemoryDraftDecisionResponse(
            outcome = OUTCOME_APPROVED,
            longTermVersion = longTermVersion,
            clearedSources = cleared,
            keptSources = kept,
            absentSources = absent,
            reason = "Approved: the long-term layer is at version $longTermVersion, $cleared source file(s) cleared" +
                (if (kept > 0) ", $kept kept for the next candidate" else "") +
                (if (absent > 0) ", $absent already gone" else ""),
        )

        /**
         * An approval that found the layer already holding exactly this text.
         *
         * Not an error and not a re-apply: it happens when a previous attempt wrote the layer and then failed
         * to clear its sources, so this one finishes the job. The version is the store's, unchanged.
         */
        fun alreadyApplied(
            longTermVersion: Long,
            cleared: Int,
            kept: Int,
            absent: Int,
        ) = MemoryDraftDecisionResponse(
            outcome = OUTCOME_APPROVED,
            longTermVersion = longTermVersion,
            clearedSources = cleared,
            keptSources = kept,
            absentSources = absent,
            reason = "Approved: the long-term layer already held this text at version $longTermVersion, " +
                "$cleared source file(s) cleared" +
                (if (kept > 0) ", $kept kept for the next candidate" else "") +
                (if (absent > 0) ", $absent already gone" else ""),
        )

        fun rejected(reason: String) = MemoryDraftDecisionResponse(outcome = OUTCOME_REJECTED, reason = reason)

        fun draftChanged(currentDigest: String) = MemoryDraftDecisionResponse(
            outcome = OUTCOME_DRAFT_CHANGED,
            reason = "The conversation merged again after you loaded this candidate; re-read it and approve the text you actually saw",
            currentDigest = currentDigest,
        )

        fun staleBase(currentBaseVersion: Long) = MemoryDraftDecisionResponse(
            outcome = OUTCOME_STALE_BASE,
            reason = "The long-term layer is at version $currentBaseVersion, but this candidate was merged against " +
                "an older one; approve the next candidate this conversation proposes instead",
            currentBaseVersion = currentBaseVersion,
        )

        fun alreadyReviewed(
            agentName: String,
            reviewedBy: String?,
            reviewedAt: LocalDateTime?,
            rejectReason: String?,
        ) = MemoryDraftDecisionResponse(
            outcome = OUTCOME_ALREADY_REVIEWED,
            reason = "The merge for '$agentName' was already decided" + (reviewedBy?.let { " by $it" } ?: "") +
                (rejectReason?.let { ": $it" } ?: ""),
            reviewedBy = reviewedBy,
            reviewedAt = reviewedAt,
            rejectReason = rejectReason,
        )
    }
}
