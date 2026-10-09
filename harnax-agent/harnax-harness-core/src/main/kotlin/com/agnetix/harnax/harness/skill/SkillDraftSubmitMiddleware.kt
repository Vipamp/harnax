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
import java.util.concurrent.ConcurrentHashMap
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
 * It is best-effort in every direction: a draft that cannot be offered this turn is still in the listing and is
 * offered again once its slot opens, so nothing here may surface as a failure to the model or to the user.
 *
 * One offer per draft per [cooldownMillis]. A staged draft stays in the listing until the session ends, so every
 * following turn would otherwise re-read, re-scan and re-post the whole staged set. Keeping the queue clean is
 * not this throttle's job: Admin answers a proposal carrying bytes it already holds with the row a reviewer has
 * seen, touching neither that row nor its trail.
 */
class SkillDraftSubmitMiddleware(
    private val sessionId: String,
    private val store: SessionSkillStore,
    private val adaptor: SkillDraftAdaptor,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scheduler: Scheduler = Schedulers.boundedElastic(),
) : MiddlewareBase {

    private val log = LoggerFactory.getLogger(SkillDraftSubmitMiddleware::class.java)
    private val lastOfferedAt = ConcurrentHashMap<String, Long>()

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
        if (names.isEmpty()) return
        val now = clock()
        names.forEach { name ->
            val claimedAt = claim(name, now) ?: return@forEach
            try {
                offer(name, claimedAt)
            } catch (e: Exception) {
                // This catch covers everything `offer` does outside the draft read and the intake call, which
                // hold their own failures. A scanner that throws must cost this one draft its turn rather than
                // every other draft's — slots were taken one name at a time, so the ones not reached yet are
                // still unclaimed and this one gives its slot back.
                log.warn(
                    "Offering the staged draft {} of session {} raised {}: {}",
                    name,
                    sessionId,
                    e.javaClass.simpleName,
                    e.message,
                )
                lastOfferedAt.remove(name, claimedAt)
            }
        }
    }

    private fun offer(
        name: String,
        claimedAt: Long,
    ) {
        val draft = try {
            store.readDraft(sessionId, name)
        } catch (e: Exception) {
            log.warn("Could not read the staged draft {}: {}", name, e.message)
            return
        } ?: run {
            // Gone, or written without any text: the listing answered and the read did not. Nothing the queue
            // can review, so nothing is filed; the slot taken this turn is kept, and the draft is offered again
            // once the window passes.
            log.warn(
                "Staged draft {} of session {} has no text to file: it is either gone or was never written",
                name,
                sessionId,
            )
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
            // Not supposed to throw. One that does has told us nothing about whether the row landed, so the
            // cooldown slot is released and the next turn offers it again rather than letting it go stale.
            log.warn("Skill draft intake for {} raised {}: {}", name, e.javaClass.simpleName, e.message)
            lastOfferedAt.remove(name, claimedAt)
            return
        }
        when (intake) {
            is SkillDraftIntake.Queued -> log.info(
                "Draft skill {} of session {} is held by the review queue as draft {}",
                name,
                sessionId,
                intake.draftId,
            )

            is SkillDraftIntake.Refused -> log.warn(
                "Draft skill {} was refused by the review queue: {}",
                name,
                intake.reason,
            )

            is SkillDraftIntake.Unavailable -> {
                log.warn(
                    "Draft skill {} could not reach the review queue: {}",
                    name,
                    intake.reason,
                )
                // Nothing was stored, so the window must not be what keeps this draft out of the queue: this
                // round's slot is released and the next turn offers it again. The two-arg remove leaves a slot
                // a later turn installed while this one was reaching the queue.
                lastOfferedAt.remove(name, claimedAt)
            }
        }
    }

    /**
     * Takes this draft's slot in the window and answers the timestamp it installed, or null while the slot is held.
     */
    private fun claim(
        name: String,
        now: Long,
    ): Long? {
        var claimedAt: Long? = null
        lastOfferedAt.compute(name) { _, previous ->
            if (previous == null || now - previous >= cooldownMillis) {
                claimedAt = now
                now
            } else {
                previous
            }
        }
        return claimedAt
    }

    companion object {
        /** Long enough to collapse one working session's turns into a single offer per draft. */
        const val DEFAULT_COOLDOWN_MILLIS = 60_000L
    }
}
