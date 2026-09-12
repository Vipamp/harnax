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
 *
 * A load that registered only *some* of the active tasks counts as failed: it leaves `lastLoadError`
 * set, which is what keeps a drifting node out of `UP`.
 *
 * The rules above are the whole of the verdict; `scheduledJobCount` below is a detail and never feeds
 * into it.
 */
@Component("scheduler")
class SchedulerHealthIndicator(
    private val status: SchedulerStatus,
    private val schedulerFactory: SchedulerFactoryBean,
    private val jobInventory: QuartzJobInventory,
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
            .withDetail("scheduledJobCount", liveJobCount())
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

    /**
     * What this instance is scheduling *right now*, read straight off the Quartz store: start/pause and
     * every CRUD move jobs without going through the load path, so the number the load remembered was
     * wrong from the first toggle onwards (that is why `SchedulerStatus.lastLoadJobCount` is no longer
     * published here). -1 when the store cannot be read at all — the count is a detail, so a failing
     * read must not decide the status, which is `quartzStarted`'s job.
     */
    private fun liveJobCount(): Int = runCatching { jobInventory.scheduledTaskIds().size }.getOrDefault(-1)
}
