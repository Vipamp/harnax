package com.agnetix.harnax.scheduler.health

import org.quartz.Scheduler
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
 * - `UP` — a reconcile round has completed with no drift left, or scheduling is disabled on this node
 * - `DOWN` — no reconcile has ever completed, or the most recent one left drift or failed
 *
 * A round that registered only *some* of the active tasks counts as failed: it leaves `lastReconcileError`
 * set, which is what keeps a drifting node out of `UP`.
 *
 * `lastReconcileAt` is this node's own last round, not the cluster's: the 60-second sweep is a singleton and
 * only the node that fires it stamps the field, so on a healthy two-node cluster the other one's can be
 * hours old. No rule here reads its age, and neither should an alert — see [SchedulerStatus.lastReconcileAt].
 *
 * The rules above are the whole of the verdict; `scheduledJobCount` and `storeType` below are details and
 * never feed into it.
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
            .withDetail("storeType", storeType(quartz))
            .withDetail("scheduledJobCount", liveJobCount())
            .withDetail("lastReconcileAt", status.lastReconcileAt?.toString() ?: "never")
        status.lastReconcileError?.let { base.withDetail("lastReconcileError", it) }

        val downReason = when {
            quartz == null || !quartz.isStarted -> "Quartz scheduler is not started"
            status.lastReconcileAt == null -> "No reconcile has succeeded since startup"
            status.lastReconcileError != null -> "Most recent reconcile failed"
            else -> null
        }

        if (downReason != null) {
            // Polled every few seconds by probes and scrapers; the reconcile loop already logged the cause.
            log.debug("Scheduler health DOWN: {}", downReason)
            return base.down().withDetail("reason", downReason).build()
        }
        return base.build()
    }

    /**
     * Which job store this node is on — `RAMJobStore`, or Boot's `LocalDataSourceJobStore` for the cluster.
     *
     * The cluster promises one thing and the yaml can say it while a stray `job-store-type` override means
     * something else, so the running value is the only one worth publishing. Quartz exposes the class
     * through its own meta-data; anything short of a readable class here is just "unknown", because the
     * store being unreadable is already what `quartzStarted` says DOWN about.
     */
    private fun storeType(quartz: Scheduler?): String = runCatching {
        quartz?.metaData?.jobStoreClass?.simpleName ?: "unknown"
    }.getOrDefault("unknown")

    /**
     * What this instance is scheduling *right now*, read straight off the Quartz store: start/pause and
     * every CRUD move jobs without going through the reconcile path, so the number the round remembered was
     * wrong from the first toggle onwards (that is why `SchedulerStatus.lastReconcileJobCount` is no longer
     * published here). -1 when the store cannot be read at all — the count is a detail, so a failing
     * read must not decide the status, which is `quartzStarted`'s job.
     */
    private fun liveJobCount(): Int = runCatching { jobInventory.scheduledTaskIds().size }.getOrDefault(-1)
}
