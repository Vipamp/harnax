package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * One memory merge that a person has not decided on yet.
 *
 * A conversation's own memory layer is a buffer; the owner's long-term memory is what every later
 * conversation of that agent reads. Nothing crosses between the two automatically: the runtime merges, files
 * the result here, and only an approval of one of these rows writes the long-term layer. That is why the row
 * carries both texts — [mergedMd] is what the reviewer reads and what the approval writes, and [baseMd] is the
 * text the merge was made against, kept so the screen can show what the candidate changes rather than only
 * what it becomes.
 *
 * The long-term layer is two things, so one row decides on both: the conclusion text above, and the agent's
 * day-by-day ledger. [targets] carries the second half — one entry per daily file the merge produced, each
 * with the text it started from and the text it would become. A row is therefore one decision over `1 + K`
 * objects, not one text: approving writes every one of them or none, because a merge that landed half of its
 * targets would leave the reviewer's queue showing a candidate that no longer describes the layer.
 *
 * [baseVersion] is the precondition for the conclusion text. The merge read the owner's layer at that store
 * version, so an approval applies this text to those bytes or says plainly that the layer moved in the
 * meantime. Each daily entry carries its own version for the same reason. A candidate approved without it
 * would let whoever decides first overwrite a merge nobody has read.
 *
 * [userId] is `sys_user.id`, the same value the store buckets the owner's memory under, so the queue and the
 * objects it decides on belong to one person by construction — a workspace admin has no row to see here.
 *
 * There is no unique key on purpose. A conversation proposes again every time its throttle window opens, and
 * the collision is resolved by the queue replacing that conversation's open candidate, not by refusing the
 * second proposal.
 */
@Schema(description = "A conversation's memory merge awaiting its owner's approval")
class MemoryDraft : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        /** Proposed, not yet decided. */
        const val STATUS_PENDING = "PENDING"

        /** Approved: the long-term layer now holds [mergedMd]. */
        const val STATUS_APPROVED = "APPROVED"

        /** Rejected by its owner, with [rejectReason]. The conversation keeps its own layer. */
        const val STATUS_REJECTED = "REJECTED"

        /**
         * The only status a proposal may write. A decided row is the record of a decision, so a later merge
         * from the same conversation must not silently reopen it.
         */
        val OPEN_STATUSES = setOf(STATUS_PENDING)
    }

    @Schema(description = "Candidate ID")
    var id: Long = 0

    @Schema(description = "Tenant resolved server-side from the session, never taken from the request body")
    var tenantId: Long = 1

    @Schema(description = "Owner whose memory bucket this merges into, as sys_user.id")
    var userId: Long = 0

    @Schema(description = "Agent bucket segment the runtime keyed the layer by")
    var agentName: String = ""

    @Schema(description = "Conversation whose own layer was merged")
    var sessionId: String = ""

    @Schema(description = "The complete new MEMORY.md the merge produced, as the reviewer reads it")
    var mergedMd: String = ""

    @Schema(description = "The owner's text the merge read; null when the owner had none yet")
    var baseMd: String? = null

    @Schema(description = "Store version that text was read at; 0 means it did not exist, so the approval is a create")
    var baseVersion: Long = 0

    @Schema(description = "JSON array of {path, content}: every conversation-layer object the merge read, with the bytes it read there")
    var sources: String? = null

    @Schema(description = "JSON array of {path, expectedVersion, baseText, mergedText}: each daily file this candidate writes in the agent's long-term layer; null when it has no daily target")
    var targets: String? = null

    @Schema(description = "PENDING / APPROVED / REJECTED")
    var status: String = STATUS_PENDING

    @Schema(description = "Reviewer username, once decided")
    var reviewedBy: String? = null

    @Schema(description = "Review time, once decided")
    var reviewedAt: LocalDateTime? = null

    @Schema(description = "Why the owner rejected it")
    var rejectReason: String? = null

    @Schema(description = "Submission time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Last proposal time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
