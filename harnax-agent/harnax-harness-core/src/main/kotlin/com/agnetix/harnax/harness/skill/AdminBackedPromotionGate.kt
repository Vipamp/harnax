package com.agnetix.harnax.harness.skill

import com.agnetix.harnax.agent.adaptor.SkillDraftAdaptor
import com.agnetix.harnax.agent.adaptor.SkillDraftIntake
import com.agnetix.harnax.agent.adaptor.SkillDraftProposal
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.skill.curator.SkillCandidate
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate.PromotionDecision
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate.PromotionDecision.Defer
import io.agentscope.harness.agent.skill.curator.SkillPromotionGate.PromotionDecision.Reject
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration

/**
 * Hands every draft the promotion pipeline reaches to Admin's review queue, and never promotes one itself.
 *
 * Upstream's `NotifyAndWaitGate` waits inside the review for somebody to answer, which needs a scheduler and
 * a replica that outlives the turn; `RejectAllGate` says no without recording anything, which leaves the
 * operator with a draft directory and no list to work from. This gate is the third shape: file the draft,
 * answer `Defer`, and let a human change the draft's fate in Admin, where the row is visible. `Defer` is
 * honest about the state — the draft stays where it is, and the same proposal offered again merges into the
 * queue row instead of stacking a second copy (design section 6.1).
 *
 * Nothing here ever returns `Approve`. Promotion moves files between two directories of one sandbox
 * workspace, while what an operator approves is a skill row in the repository Admin serves to the tenant —
 * a different object reached by a different path, which is why approving a queue row installs the skill and
 * leaves this directory alone.
 *
 * A `DANGEROUS` scan verdict cannot arrive here: upstream blocks it in the promoter before the gate is
 * called, so the queue only holds what the scanner let through, and a reviewer deciding on that verdict is
 * deciding on the findings rather than on the pass.
 *
 * Runs off the calling thread. `review` is reached from the turn that proposed the skill, and an HTTP call
 * that blocked there would hold up the answer for a decision nobody is waiting on synchronously.
 */
class AdminBackedPromotionGate(
    private val sessionId: String,
    private val adaptor: SkillDraftAdaptor,
    private val files: SkillDraftFilesReader,
    private val retryAfter: Duration = DEFAULT_RETRY_AFTER,
) : SkillPromotionGate {

    private val log = LoggerFactory.getLogger(AdminBackedPromotionGate::class.java)

    override fun review(
        candidate: SkillCandidate?,
        ctx: RuntimeContext?,
    ): Mono<PromotionDecision> = Mono.fromCallable { decide(candidate, ctx) }.subscribeOn(Schedulers.boundedElastic())

    private fun decide(
        candidate: SkillCandidate?,
        ctx: RuntimeContext?,
    ): PromotionDecision {
        if (candidate == null) return Reject("no candidate to review", REVIEWER)
        val name = candidate.name()?.takeIf { it.isNotBlank() }
            ?: return Reject("draft carries no skill name", REVIEWER)
        val proposal = SkillDraftProposal(
            sessionId = sessionId,
            name = name,
            description = candidate.description(),
            skillmd = candidate.skillMdContent().orEmpty(),
            // The candidate's own script previews are heads of 40 lines at most; the queue needs the bodies
            // the reviewer will later install, so they are read back off the workspace.
            resources = files.read(name, ctx),
            scanVerdict = candidate.securityScan()?.verdict()?.name,
            scanFindings = findingTexts(candidate.securityScan()?.findings().orEmpty()),
        )
        val intake = try {
            adaptor.submit(proposal)
        } catch (e: Exception) {
            // The adaptor is not supposed to throw. One that does has told us nothing about whether the row
            // landed, so answer with the one decision that cannot lose a draft: offer it again later rather
            // than refuse it for a fault in our own plumbing.
            log.warn(
                "Skill draft intake for {} raised {}: {}",
                name,
                e.javaClass.simpleName,
                e.message,
            )
            SkillDraftIntake.Unavailable(e.message ?: e.javaClass.simpleName)
        }
        return when (intake) {
            is SkillDraftIntake.Queued -> {
                log.info("Draft skill {} is held by the review queue as draft {}", name, intake.draftId)
                Defer(retryAfter, "queued for human review as draft ${intake.draftId}")
            }

            is SkillDraftIntake.Refused -> {
                log.warn("Draft skill {} was refused by the review queue: {}", name, intake.reason)
                Reject(intake.reason, REVIEWER)
            }

            is SkillDraftIntake.Unavailable -> {
                log.warn("Draft skill {} could not reach the review queue: {}", name, intake.reason)
                Defer(retryAfter, "skill draft intake unavailable: ${intake.reason}")
            }
        }
    }

    companion object {
        /** Upstream's own interval for a gate that waits on a human; nothing schedules it, it is what a deferred draft reports. */
        private val DEFAULT_RETRY_AFTER: Duration = Duration.ofHours(24)

        /** The admin audit sentinel: no human was on this path, the runtime filed the draft. */
        private const val REVIEWER = "system"
    }
}
