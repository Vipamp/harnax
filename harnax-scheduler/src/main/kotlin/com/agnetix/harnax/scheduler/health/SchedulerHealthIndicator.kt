package com.agnetix.harnax.scheduler.health

import org.slf4j.LoggerFactory
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.stereotype.Component

/**
 * Reports whether this instance is actually scheduling anything, under `scheduler`.
 *
 * Kept out of the liveness probe on purpose: an instance that failed its initial load is broken
 * in a way a container restart does not fix (the database or the task table is the problem), and
 * killing the process would also kill the retry loop that is waiting for it to come back. Deployment
 * probes stay on `/actuator/health/liveness`; this indicator is for `/actuator/health` and alerting.
 *
 * Status rules:
 * - `UP` — a load has succeeded and nothing has failed since, or scheduling is disabled on this node
 * - `DOWN` — no load has ever succeeded, or the most recent load failed
 */
@Component("scheduler")
class SchedulerHealthIndicator(
    private val status: SchedulerStatus,
    private val schedulerFactory: SchedulerFactoryBean,
) : HealthIndicator {

    private val log = LoggerFactory.getLogger(SchedulerHealthIndicator::class.java)

    override fun health(): Health {
        if (!status.schedulerEnabled) {
            return Health.up().withDetail("enabled", false).build()
        }

        val quartz = runCatching { schedulerFactory.scheduler }.getOrNull()
        val base = Health.up()
            .withDetail("quartzStarted", quartz?.isStarted ?: false)
            .withDetail("instanceId", quartz?.metaData?.schedulerInstanceId ?: "unknown")
            .withDetail("scheduledJobCount", status.scheduledJobCount)
            .withDetail("lastLoadSuccessAt", status.lastLoadSuccessAt?.toString() ?: "never")
        status.lastLoadError?.let { base.withDetail("lastLoadError", it) }

        val downReason = when {
            quartz == null || !quartz.isStarted -> "Quartz scheduler is not started"
            status.lastLoadSuccessAt == null -> "No task load has succeeded since startup"
            status.lastLoadError != null -> "Most recent task load failed"
            else -> null
        }

        if (downReason != null) {
            // Polled every few seconds by probes and scrapers; the load loop already logged the cause.
            log.debug("Scheduler health DOWN: {}", downReason)
            return base.down().withDetail("reason", downReason).build()
        }
        return base.build()
    }
}
