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
    // than from `SchedulerService`: that service depends on *this* bean to count its load attempts, so
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
     * One round's verdict, from either caller: the startup converge loop and the 60-second sweep. The name
     * is the one this meter shipped with before reconcile replaced the startup load; since that sweep exists,
     * the rate is "rounds per minute on this node" and not "restarts per hour", so only the `failure` tag is
     * worth alerting on.
     */
    fun recordLoadAttempt(success: Boolean) {
        registry.counter(
            "scheduler.load.attempts",
            "outcome",
            if (success) "success" else "failure",
        ).increment()
    }
}
