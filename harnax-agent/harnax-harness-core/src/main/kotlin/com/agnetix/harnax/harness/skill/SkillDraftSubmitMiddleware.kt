package com.agnetix.harnax.harness.skill

import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.middleware.AgentInput
import io.agentscope.core.middleware.MiddlewareBase
import io.agentscope.harness.agent.skill.curator.SkillPromoter
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

/**
 * Offers the drafts an agent wrote during this turn to the review queue, once the turn is over.
 *
 * This trigger is harnax's own because upstream has none. With `autoPromote=false`, neither `skill_manage`
 * nor `propose_skill` calls the promoter, the curator middleware only runs lifecycle transitions, and the
 * sole caller of the promotion pipeline is `HarnessAgent.promoteSkill(...)` — which nothing calls on this
 * deployment. Without it a draft would sit in the staging directory forever and Admin's queue would stay
 * empty, so the review step the whole design exists for would never be reached.
 *
 * The offer runs after the answer, not inside it: the scan and the intake are an HTTP round trip, and a user
 * should not wait on a review nobody has opened yet. It is also best-effort in every direction — a draft that
 * cannot be offered this turn is still on disk and gets offered next turn, so nothing here may surface as a
 * failure to the model or to the user.
 *
 * One offer per draft per [cooldownMillis]. A working session rewrites the same draft several turns in a row,
 * and Admin already merges a resubmission of a pending name into its existing queue row, so the window buys
 * nothing except a glob and a scan per turn.
 */
class SkillDraftSubmitMiddleware(
    private val staging: SkillDraftStaging,
    private val reviewerId: String = SYSTEM_REVIEWER,
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
        .doOnComplete { scheduler.schedule { offerStagedDrafts(ctx) } }

    private fun offerStagedDrafts(ctx: RuntimeContext) {
        val names = try {
            staging.listDraftNames(ctx)
        } catch (e: Exception) {
            log.warn("Could not list the staged drafts: {}", e.message)
            return
        }
        if (names.isEmpty()) return
        val now = clock()
        names.filter { claim(it, now) }.forEach { offer(it, ctx) }
    }

    private fun offer(
        name: String,
        ctx: RuntimeContext,
    ) {
        val promotion = try {
            staging.promote(name, reviewerId, ctx)
        } catch (e: Exception) {
            log.warn("Could not start the promotion of draft {}: {}", name, e.message)
            return
        } ?: return
        promotion.subscribe(
            { result -> report(name, result) },
            { e -> log.warn("Promotion of draft {} failed: {}", name, e.message) },
        )
    }

    private fun report(
        name: String,
        result: SkillPromoter.PromotionResult,
    ) {
        // A deferred draft is the normal outcome: the gate filed it in Admin and answered for a human to
        // decide. Anything the pipeline itself refused is worth a warning, because that draft will look
        // missing from the queue to whoever expected the agent's proposal there.
        if (result.status() == SkillPromoter.PromotionResult.Status.INVALID) {
            log.warn("Draft {} is staged but cannot be promoted: {}", name, result.message())
        } else {
            log.debug("Draft {}: {} ({})", name, result.status(), result.message())
        }
    }

    /**
     * Takes this draft's slot in the window, so two turns ending at once cannot both offer it.
     */
    private fun claim(
        name: String,
        now: Long,
    ): Boolean {
        var claimed = false
        lastOfferedAt.compute(name) { _, previous ->
            if (previous == null || now - previous >= cooldownMillis) {
                claimed = true
                now
            } else {
                previous
            }
        }
        return claimed
    }

    companion object {
        /** Long enough to collapse one working session's turns into a single offer per draft. */
        const val DEFAULT_COOLDOWN_MILLIS = 60_000L

        /** The same sentinel [AdminBackedPromotionGate] signs with: no human was on this path. */
        const val SYSTEM_REVIEWER = "system"
    }
}
