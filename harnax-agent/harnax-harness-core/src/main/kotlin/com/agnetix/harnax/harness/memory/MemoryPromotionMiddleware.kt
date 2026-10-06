package com.agnetix.harnax.harness.memory

import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.middleware.AgentInput
import io.agentscope.core.middleware.MiddlewareBase
import io.agentscope.harness.agent.coordination.PeriodicGate
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.function.Function

/**
 * Gives a conversation's memory layer a reader of its own: after each turn, promotes it into the owner's
 * long-term layer when the throttle says it is time.
 *
 * The trigger is a timer, not a session-end, because harnax has no reliable session-end signal — most
 * conversations simply stop receiving requests, and clearing or deleting a conversation only happens when a
 * user asks (design 11.4). Keying the window on the conversation is what buys the third hard rule of that
 * section: two conversations of one agent promote independently, so neither waits on the other's clock.
 *
 * Nothing here is on the answer path. The claim is remote I/O under a store-backed gate and the merge is a
 * model call, so both run on the blocking-friendly scheduler after the turn has completed; a promotion that
 * fails leaves one log line and a conversation that got its answer anyway. The in-flight counter is bumped
 * synchronously so [io.agentscope.harness.agent.HarnessAgent.close] cannot quiesce while a merge is mid-write.
 */
class MemoryPromotionMiddleware(
    private val promoter: MemoryPromoter,
    private val gate: PeriodicGate,
    private val minGap: Duration,
    private val sessionId: String,
) : MiddlewareBase {

    override fun onAgent(
        agent: Agent,
        ctx: RuntimeContext,
        input: AgentInput,
        next: Function<AgentInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> = next.apply(input).doOnComplete {
        MemoryBackgroundTasks.begin()
        Mono.fromRunnable<Unit> {
            if (gate.tryClaim(slotOf(sessionId), minGap)) {
                promoter.promoteNow()
            }
        }.subscribeOn(Schedulers.boundedElastic())
            .doFinally { MemoryBackgroundTasks.end() }
            .subscribe({ }, { e -> log.warn("Memory promotion failed: {}", e.message) })
    }

    companion object {

        private val log = LoggerFactory.getLogger(MemoryPromotionMiddleware::class.java)

        /**
         * The gate's operation prefix, kept separate from the flush and maintenance slots that share the same
         * gate: three kinds of background work with one clock each.
         *
         * The scope segment mirrors upstream's spelling, and the tail is the conversation rather than the
         * owner — which is the point of the layer: promotion is per conversation, so each one of them can
         * drain its own buffer on its own schedule.
         */
        fun slotOf(sessionId: String): String = "memory-promotion:SESSION:$sessionId"
    }
}
