package com.agnetix.harnax.scheduler.metrics

import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Meters for the scheduler's own bookkeeping.
 *
 * None of them moves at request rate — the busiest caller is the reconcile sweep, once a minute, and only
 * `/reload` reaches them from a request — so they go through [MeterRegistry.counter] and [Gauge] directly
 * instead of the pre-cached-field style the router uses on its per-request path.
 */
@Component
class SchedulerMetrics(
    private val registry: MeterRegistry,

    // The job count comes from [QuartzJobInventory] — a bean that only knows the Quartz store — rather
    // than from `SchedulerService`: that service depends on *this* bean to count its reconcile rounds, so
    // reading the live count through it is a construction cycle. Going through the inventory keeps the
    // observation layer below the business layer and needs no lazy proxy to stay bootable.
    private val jobInventory: QuartzJobInventory,
) {

    @PostConstruct
    fun initMeters() {
        // Live off the Quartz store, not off what the startup load remembered: start/pause and every CRUD
        // move jobs without going through that path, so a load-time number froze the gauge at whatever the
        // instance happened to load minutes or hours ago. NaN when the store cannot be read, so a broken
        // job store shows up as "no sample" instead of a plausible zero.
        Gauge.builder("scheduler.jobs.scheduled", jobInventory) { inventory ->
            runCatching { inventory.scheduledTaskIds().size.toDouble() }.getOrDefault(Double.NaN)
        }
            .description("Agent tasks registered in the shared Quartz store (cluster view when job-store-type=jdbc)")
            .register(registry)
    }

    /**
     * Divergence the reconcile had to repair, counted per action. A steady non-zero stream here means CRUD
     * notifications and the store are out of step — the failure mode a broadcast-per-node design hid.
     * Registered lazily so a round that changed nothing costs no samples.
     *
     * **Per node, about cluster work.** A scheduled round is a cluster singleton, so a quiet cluster
     * publishes from one instance only; but admin's `/reload` reaches every enabled node and each of them
     * converges the same diff, so the repair is counted once per node. Summing this series over `instance`
     * therefore over-reports by the node count — read it per instance (or with `max()`), and alert on
     * "any instance non-zero", which is what the meter is for.
     */
    fun recordReconcileDrift(
        action: String,
        count: Int,
    ) {
        if (count <= 0) {
            return
        }
        registry.counter("scheduler.reconcile.drift", "action", action).increment(count.toDouble())
    }

    /**
     * One reconcile round's verdict, from whichever caller ran it: the startup converge loop, admin's
     * `/reload` forward or the 60-second sweep.
     *
     * The meter used to be `scheduler.load.attempts`, which was honest when the only caller was the startup
     * load — one to n samples per process start. The sweep made it one sample per minute per enabled node, so
     * the name started lying about every `rate()` panel and about the "restarts that failed to schedule"
     * reading in particular. Renamed rather than kept-and-documented: no dashboard consumes it yet, and the
     * old name is the bug.
     */
    fun recordReconcileRound(success: Boolean) {
        registry.counter(
            "scheduler.reconcile.rounds",
            "outcome",
            if (success) "success" else "failure",
        ).increment()
    }
}
