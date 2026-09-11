package com.agnetix.harnax.scheduler.metrics

import com.agnetix.harnax.scheduler.health.SchedulerStatus
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
    private val status: SchedulerStatus,
) {

    @PostConstruct
    fun initMeters() {
        Gauge.builder("scheduler.jobs.scheduled", status) { it.scheduledJobCount.toDouble() }
            .description("Agent tasks currently registered in this instance's scheduler")
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
