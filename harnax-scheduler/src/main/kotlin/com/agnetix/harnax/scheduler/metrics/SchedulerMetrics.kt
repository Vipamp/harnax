package com.agnetix.harnax.scheduler.metrics

import com.agnetix.harnax.scheduler.service.SchedulerService
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Lazy
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

    // [Lazy] breaks what would otherwise be a construction cycle: SchedulerServiceImpl depends on this
    // bean to count its load attempts. Spring injects a proxy here and the real service is only reached
    // when the gauge is scraped, which is always after both beans exist.
    @Lazy private val schedulerService: SchedulerService,
) {

    @PostConstruct
    fun initMeters() {
        // Live off the Quartz store, not off what the startup load remembered: start/pause and every CRUD
        // move jobs without going through that path, so a load-time number froze the gauge at whatever the
        // instance happened to load minutes or hours ago. NaN when the store cannot be read, so a broken
        // job store shows up as "no sample" instead of a plausible zero.
        Gauge.builder("scheduler.jobs.scheduled", schedulerService) { service ->
            runCatching { service.getScheduledTaskIds().size.toDouble() }.getOrDefault(Double.NaN)
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
