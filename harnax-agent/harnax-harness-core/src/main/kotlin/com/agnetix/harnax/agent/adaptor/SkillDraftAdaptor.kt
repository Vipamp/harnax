package com.agnetix.harnax.agent.adaptor

/**
 * Files one agent-authored skill draft with Admin's review queue.
 *
 * The runtime holds no queue of its own. A draft an agent wrote into its workspace has to reach the people
 * who own the tenant it belongs to, and the only path there is the internal API that already delivers their
 * skills — so this crosses it in the other direction (design section 6.1).
 *
 * The call is allowed to take as long as the network needs, unlike [SkillUsageAdaptor]: it is reached from a
 * promotion gate that runs off the inference path, and its answer decides what the gate reports. It is still
 * not allowed to throw — the caller has to tell a refusal apart from an outage, and an exception erases that
 * difference. Report [SkillDraftIntake.Unavailable] for anything the caller could succeed at by trying
 * again later, and [SkillDraftIntake.Refused] for anything that will fail the same way forever.
 */
fun interface SkillDraftAdaptor {
    fun submit(
        proposal: SkillDraftProposal,
    ): SkillDraftIntake
}

/**
 * One draft as the gate saw it.
 *
 * [resources] carries the full text of every support file, keyed by its path inside the skill directory.
 * Upstream's review candidate carries only the first 40 lines of each script, and a queue that stored those
 * would show a reviewer a truncated file while promoting the whole one — so the reader behind the gate goes
 * back to the workspace for the complete bytes.
 *
 * [sessionId] is the harnax session id, and it is what decides the tenant: Admin resolves the owner from
 * that id rather than from anything the runtime claims.
 */
data class SkillDraftProposal(
    val sessionId: String,
    val name: String,
    val description: String?,
    val skillmd: String,
    val resources: Map<String, String>,
    val scanVerdict: String?,
    val scanFindings: List<String>,
)

/**
 * Where the draft ended up.
 *
 * Three answers because a queue has to distinguish them: a draft nobody is waiting on is worse than no
 * draft at all, while a draft refused for its content must never be offered to the model again as "pending".
 */
sealed interface SkillDraftIntake {
    /** Accepted; [draftId] is the queue row this proposal maps to, whether the queue filed it or already held it. */
    data class Queued(val draftId: Long) : SkillDraftIntake

    /** Accepted by the transport and rejected by the product — retrying sends the same refusal. */
    data class Refused(val reason: String) : SkillDraftIntake

    /** Nothing was stored because Admin or the network could not be reached. */
    data class Unavailable(val reason: String) : SkillDraftIntake
}
