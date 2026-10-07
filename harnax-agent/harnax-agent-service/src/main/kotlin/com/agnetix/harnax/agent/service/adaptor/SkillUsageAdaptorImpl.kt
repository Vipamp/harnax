package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.SkillUsageAdaptor
import com.agnetix.harnax.agent.service.client.AdminApiClient
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Posts skill VIEW and USE events to Admin over HTTP, on a thread that is not the model's.
 *
 * Both callers sit on the path that streams an answer — the load comes out of system-prompt composition, the
 * use out of the acting stream — so the call is offloaded rather than made inline: a slow or unreachable Admin
 * must not add its latency to every model call, and it certainly must not fail the turn. Admin being down is a
 * reporting outage, not an inference outage.
 *
 * One worker and a short queue, because the events are counters. When the queue is full the newest batch is
 * dropped rather than the backlog grown, which is also what bounds memory when Admin stays unreachable for a
 * long time. The recorder upstream re-reports a load once its cooldown window passes, so a dropped VIEW costs
 * one window of one count; a dropped USE is gone for good, since one turn uses a skill once — the trade every
 * counter here makes, a missing number rather than a stalled answer.
 */
@Component
class SkillUsageAdaptorImpl(
    private val adminApiClient: AdminApiClient,
) : SkillUsageAdaptor {

    private val log = LoggerFactory.getLogger(SkillUsageAdaptorImpl::class.java)

    /** Counted rather than silently discarded: "the usage page is empty" needs a number proving the queue overflowed. */
    private val dropped = AtomicLong()

    private val reporter = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
    ) { runnable ->
        Thread(runnable, "skill-usage-reporter").apply { isDaemon = true }
    }

    override fun reportViews(
        sessionId: String,
        skillIds: List<Long>,
        userId: Long?,
    ) = submit(sessionId, skillIds, userId, EVENT_VIEW)

    override fun reportUses(
        sessionId: String,
        skillIds: List<Long>,
        userId: Long?,
    ) = submit(sessionId, skillIds, userId, EVENT_USE)

    private fun submit(
        sessionId: String,
        skillIds: List<Long>,
        userId: Long?,
        event: String,
    ) {
        if (skillIds.isEmpty()) return
        try {
            reporter.execute {
                try {
                    // The client turns transport failures into `false`; logged here too, so the reason for a
                    // missing count survives even when Admin answered with a business error instead of throwing.
                    if (!adminApiClient.reportSkillUsage(sessionId, skillIds, userId, event)) {
                        log.debug("Skill {} batch for session {} ({} skill(s)) was not accepted by Admin", event, sessionId, skillIds.size)
                    }
                } catch (e: Exception) {
                    // Caught rather than left to kill the worker: an implementation that throws is breaking
                    // its side of the contract, and the batches after it still owe Admin a count.
                    log.warn("Skill {} batch for session {} ({} skill(s)) failed: {}", event, sessionId, skillIds.size, e.message)
                }
            }
        } catch (e: Exception) {
            // RejectedExecutionException, both when the queue is full and after shutdown. A counter
            // never reaches the caller: this is the last line of the non-blocking, never-throws contract.
            val total = dropped.incrementAndGet()
            if (total == 1L || total % DROP_LOG_EVERY == 0L) {
                log.warn("Skill {} batch for session {} dropped ({} batches dropped so far): {}", event, sessionId, total, e.message)
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        // Give what is already queued a few seconds, then stop: pending events are counters, not state.
        reporter.shutdown()
        try {
            if (!reporter.awaitTermination(5, TimeUnit.SECONDS)) reporter.shutdownNow()
        } catch (e: InterruptedException) {
            reporter.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val QUEUE_CAPACITY = 64
        private const val DROP_LOG_EVERY = 50L
        private const val EVENT_VIEW = "VIEW"
        private const val EVENT_USE = "USE"
    }
}
