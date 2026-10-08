package com.agnetix.harnax.agent.adaptor

/**
 * Files one conversation's merged memory with Admin's review queue.
 *
 * A conversation's own memory layer is private to it, and the owner's long-term layer is what every later
 * conversation reads. Nothing in the runtime crosses between the two any more: a merge is proposed here and a
 * person decides it (design 11.4). The runtime holds no queue, so the internal API that already delivers an
 * agent's configuration carries the proposal in the other direction.
 *
 * The call may take as long as the network needs, like [SkillDraftAdaptor]: it is reached from a throttled
 * background stage, off the answer path. It must not throw — the caller has to tell a refusal apart from an
 * outage, and an exception erases that difference. Report [MemoryDraftIntake.Unavailable] for anything that
 * could succeed on a later window, and [MemoryDraftIntake.Refused] for anything that would fail the same way
 * forever.
 */
fun interface MemoryDraftAdaptor {
    fun propose(
        proposal: MemoryDraftProposal,
    ): MemoryDraftIntake
}

/**
 * One merge as the promotion pass saw it, in the shape a reviewer can decide.
 *
 * [sessionId] is the harnax session id of the conversation the layer belongs to, and it is what decides the
 * tenant: Admin resolves the owner and the agent from that id rather than from anything claimed here.
 * [agentName] is the bucket segment this conversation's routes were mounted under, carried only so Admin can
 * refuse a proposal whose session and agent do not belong to each other.
 *
 * [baseMarkdown] and [baseVersion] are the owner's long-term text and the store version it was read at, so
 * approving applies this merge to the bytes it was made against — or says plainly that the layer moved in the
 * meantime. A null [baseMarkdown] is a layer with nothing curated yet.
 *
 * [sources] are the conversation's own objects this merge took its material out of, with the exact content
 * each one held. Approval clears those and only those, and only while they still hold these bytes: the flush
 * of a later turn appends to the same daily ledger, and an entry no merge ever saw must not leave the bucket.
 */
data class MemoryDraftProposal(
    val sessionId: String,
    val agentName: String,
    val mergedMarkdown: String,
    val baseMarkdown: String?,
    val baseVersion: Long,
    val sources: List<MemoryDraftSource>,
)

/** One object of the conversation's layer: where it lives under that layer and what this pass read there. */
data class MemoryDraftSource(
    val path: String,
    val content: String,
)

/**
 * Where the proposal ended up.
 *
 * Three answers because a queue has to distinguish them: a merge nobody is waiting to decide is worse than no
 * merge at all, while a merge refused for its session must never be filed again as "pending".
 */
sealed interface MemoryDraftIntake {
    /** Accepted; [draftId] is the queue row a reviewer will open. */
    data class Queued(val draftId: Long) : MemoryDraftIntake

    /** Accepted by the transport and rejected by the product — retrying sends the same refusal. */
    data class Refused(val reason: String) : MemoryDraftIntake

    /** Nothing was stored because Admin or the network could not be reached. */
    data class Unavailable(val reason: String) : MemoryDraftIntake
}
