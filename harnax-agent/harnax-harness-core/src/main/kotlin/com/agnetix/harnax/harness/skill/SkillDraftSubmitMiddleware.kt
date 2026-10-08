package com.agnetix.harnax.harness.skill

import com.agnetix.harnax.agent.adaptor.SkillDraftAdaptor
import com.agnetix.harnax.agent.adaptor.SkillDraftIntake
import com.agnetix.harnax.agent.adaptor.SkillDraftProposal
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.middleware.AgentInput
import io.agentscope.core.middleware.MiddlewareBase
import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.function.Function

/**
 * Offers the drafts an agent wrote during this turn straight to Admin's review queue, once the turn is over.
 *
 * This trigger is harnax's own because upstream has none. With `autoPromote=false`, neither `skill_manage`
 * nor `propose_skill` calls the promoter, the curator middleware only runs lifecycle transitions, and the
 * sole caller of the promotion pipeline is `HarnessAgent.promoteSkill(...)` — which nothing calls on this
 * deployment. Without it a draft would sit in the staging directory forever and Admin's queue would stay
 * empty, so the review step the whole design exists for would never be reached.
 *
 * The offer runs after the answer, not inside it: the scan and the intake are an HTTP round trip, and a user
 * should not wait on a review nobody has opened yet. It reads through [SessionSkillStore] rather than through
 * the agent's workspace filesystem precisely because it is late — `SandboxLifecycleMiddleware` has unbound the
 * sandbox by the time this runs, so every workspace read answers `No active sandbox` and the queue is never
 * fed. A container handle resolved out of call still answers on that turn.
 *
 * It is best-effort in every direction: a draft that cannot be offered this turn is still in the listing and
 * gets offered again on the next one, so nothing here may surface as a failure to the model or to the user.
 *
 * Everything the listing answers is offered, every turn: design §4.2 took the window out of this object, and a
 * resubmission of a name the queue still holds pending is merged into that row by Admin rather than stacked as
 * a second one.
 */
class SkillDraftSubmitMiddleware(
    private val sessionId: String,
    private val store: SessionSkillStore,
    private val adaptor: SkillDraftAdaptor,
    private val scheduler: Scheduler = Schedulers.boundedElastic(),
) : MiddlewareBase {

    private val log = LoggerFactory.getLogger(SkillDraftSubmitMiddleware::class.java)

    override fun onAgent(
        agent: Agent,
        ctx: RuntimeContext,
        input: AgentInput,
        next: Function<AgentInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> = next.apply(input)
        .doOnComplete { scheduler.schedule { offerStagedDrafts() } }

    private fun offerStagedDrafts() {
        val names = try {
            store.listDraftNames(sessionId)
        } catch (e: Exception) {
            log.warn("Could not list the staged drafts of session {}: {}", sessionId, e.message)
            return
        }
        names.forEach { offer(it) }
    }

    private fun offer(name: String) {
        val draft = try {
            store.readDraft(sessionId, name)
        } catch (e: Exception) {
            log.warn("Could not read the staged draft {}: {}", name, e.message)
            return
        } ?: run {
            // Gone, or written without any text: the listing answered and the read did not. Nothing the queue
            // can review, so nothing is filed — and the draft is still in the listing for the next turn.
            log.warn("Staged draft {} has no text to file: it is either gone or was never written", name)
            return
        }
        val scan = SkillSecurityScanner.scan(name, draft.skillmd, draft.resources)
        if (!SkillSecurityScanner.shouldAllow(SkillSecurityScanner.TrustLevel.AGENT_CREATED, scan.verdict())) {
            log.warn("Staged draft {} is {} and is not filed: {}", name, scan.verdict(), scan.findings().size)
            return
        }
        val intake = try {
            adaptor.submit(
                SkillDraftProposal(
                    sessionId = sessionId,
                    name = name,
                    description = draft.description,
                    skillmd = draft.skillmd,
                    resources = draft.resources,
                    scanVerdict = scan.verdict().name,
                    scanFindings = findingTexts(scan.findings()),
                ),
            )
        } catch (e: Exception) {
            // Not supposed to throw. It is logged and dropped rather than re-raised: the answer has already
            // been streamed, and this draft is still in the listing for the next turn.
            log.warn("Skill draft intake for {} raised {}: {}", name, e.javaClass.simpleName, e.message)
            return
        }
        when (intake) {
            is SkillDraftIntake.Queued -> log.info(
                "Draft skill {} is queued for review as draft {}",
                name,
                intake.draftId,
            )

            is SkillDraftIntake.Refused -> log.warn(
                "Draft skill {} was refused by the review queue: {}",
                name,
                intake.reason,
            )

            is SkillDraftIntake.Unavailable -> log.warn(
                "Draft skill {} could not reach the review queue: {}",
                name,
                intake.reason,
            )
        }
    }
}
