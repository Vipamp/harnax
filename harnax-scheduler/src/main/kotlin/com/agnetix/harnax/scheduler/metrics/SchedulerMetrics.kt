package com.agnetix.harnax.scheduler.metrics

import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * Meters for the scheduler's own bookkeeping.
 *
 * Both meters move a handful of times per minute, so they go through [MeterRegistry.counter] and
 * [Gauge] directly instead of the pre-cached-field style the router uses on its per-request path.
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
            .description("Agent tasks registered in the Quartz store this instance reads")
            .register(registry)
    }

    fun recordLoadAttempt(success: Boolean) {
        registry.counter(
            "scheduler.load.attempts",
            "outcome",
            if (success) "success" else "failure",
        ).increment()
    }
}
