package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.RegisteredJob
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.metrics.SchedulerMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** What one reconcile round did. `failedIds` is the drift an operator has to see. */
data class ReconcileReport(
    val added: Int,
    val removed: Int,
    val updated: Int,
    val unchanged: Int,
    val failedIds: List<Long>,
) {
    val converged: Boolean get() = failedIds.isEmpty()
}

/**
 * Converge the Quartz store with `agent_task`, without ever unregistering a schedule that is already right.
 *
 * This replaces "delete every job in AgentTaskGroup, then re-register everything". Under a shared store
 * that is a cluster-wide unschedule on every node start, and every fire the surviving nodes claimed inside
 * that window is gone; it also wiped PREV_FIRE_TIME/NEXT_FIRE_TIME on jobs that had not changed. The diff
 * is what makes the shared store safe: a restart touches only the tasks that actually moved.
 *
 * The same call serves three triggers, and that is the reason it is one function rather than three:
 * node startup, after a CRUD (admin's forward), and a 60-second cluster-singleton sweep. The third is what
 * bounds the damage of a lost CRUD notification — the write and the schedule are not one transaction, so a
 * node that was down during an edit would otherwise stay wrong forever.
 */
@Component
class TaskScheduleReconciler(
    private val agentTaskMapper: AgentTaskMapper,
    private val registrar: TaskQuartzRegistrar,
    private val inventory: QuartzJobInventory,
    private val status: SchedulerStatus,
    private val metrics: SchedulerMetrics,
) {

    private val log = LoggerFactory.getLogger(TaskScheduleReconciler::class.java)

    /**
     * The four counts are a partition of the diff, so every active task lands in exactly one of them and
     * [ReconcileReport.unchanged] is observable — that is the number proving a restart touched nothing it
     * did not have to. A task whose store write failed is counted in the bucket the diff put it in *and* in
     * [ReconcileReport.failedIds]; the next round retries it.
     */
    fun reconcile(): ReconcileReport {
        // The store first, then the table, and the order is load-bearing rather than stylistic. A CRUD that
        // commits between the two reads (admin's one `/reload` forward after every committed CRUD makes that
        // the routine path, not an edge case) must land in the table but not in the snapshot, so this round
        // re-registers it — a benign `replace=true` write. Read the table first and the same commit lands only
        // in the snapshot: the job then looks like an extra key and this round deletes a schedule the cluster
        // just asked for, taking whatever cron boundary fell inside the window with it.
        val actual = inventory.agentTaskJobs()
        val expected = agentTaskMapper.selectRunningTasks().associateBy { it.id }

        var added = 0
        var removed = 0
        var updated = 0
        var unchanged = 0
        // Two directions, because they are two different problems for whoever reads the health detail: a
        // task the store will not take, and a job the store will not give back.
        val failedApply = mutableListOf<Long>()
        val failedRemoval = mutableListOf<Long>()

        for ((taskId, task) in expected) {
            val registered = actual[taskId]
            when {
                registered == null -> {
                    added++
                    applyDiff(failedApply, taskId) { registrar.register(task) }
                }

                !matches(task, registered) -> {
                    updated++
                    applyDiff(failedApply, taskId) { registrar.register(task) }
                }

                else -> unchanged++
            }
        }
        for (taskId in actual.keys - expected.keys) {
            removed++
            applyDiff(failedRemoval, taskId) { registrar.unregister(taskId) }
        }

        reportDrift(added, removed, updated)
        val drift = describeDrift(failedApply, failedRemoval, expected.size)
        val scheduled = unchanged + updated + added - failedApply.size
        status.recordReconcile(scheduled, drift)
        metrics.recordReconcileRound(success = drift == null)
        logRound(added, removed, updated, unchanged, scheduled)
        return ReconcileReport(added, removed, updated, unchanged, failedApply + failedRemoval)
    }

    /**
     * Whether the stored job *is* what the table asks for.
     *
     * The cron is compared case-insensitively on purpose: Quartz normalizes it. `CronExpression`'s String
     * constructor uppercases its argument, `CronTriggerImpl.getCronExpression()` hands that back, and the
     * JDBC store persists and re-reads exactly it — so the webui's own `0 0 9 ? * MON` preset sits in
     * `agent_task` as typed and comes out of the store uppercased. A case-sensitive compare calls every
     * lettered cron a change: `updated++` and a `scheduleJob(replace=true)` on every 60-second round, which
     * deletes the trigger row, recomputes NEXT_FIRE_TIME and clears PREV_FIRE_TIME. That is the state this
     * class exists to make impossible, permanently, and it feeds the drift metric that was added to say
     * "CRUD and the store are out of step".
     *
     * An old job that predates the concurrent flag, or a task whose flag moved, is a real change and must be
     * re-registered.
     */
    private fun matches(
        task: AgentTask,
        registered: RegisteredJob,
    ): Boolean = registered.cronExpression.equals(task.cronExpression, ignoreCase = true) &&
        registered.jobClassName == TaskQuartzRegistrar.jobClassFor(task).name

    /**
     * One task's store write, with its failure recorded as drift instead of thrown: a single unusable cron
     * must not stop the other tasks in the round, and the 60-second sweep gets another attempt at it.
     */
    private inline fun applyDiff(
        failed: MutableList<Long>,
        taskId: Long,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            failed += taskId
            log.error("Reconcile could not apply task {}: {}", taskId, e.message, e)
        }
    }

    /**
     * Only the buckets that moved publish a sample: a round that changed nothing — which is every round on
     * a healthy cluster — must not create three zero-valued counters per minute.
     */
    private fun reportDrift(
        added: Int,
        removed: Int,
        updated: Int,
    ) {
        if (added > 0) {
            metrics.recordReconcileDrift("add", added)
        }
        if (removed > 0) {
            metrics.recordReconcileDrift("remove", removed)
        }
        if (updated > 0) {
            metrics.recordReconcileDrift("update", updated)
        }
    }

    /**
     * Null means the store now matches the table. The text doubles as the /actuator/health detail.
     *
     * The two directions say different things, because they are different work for the operator reading it:
     * the first is an active task the store refused (its schedule is missing), the second a job the store
     * refused to give back (a delete that has not landed). One sentence covering both used to announce a
     * failed *unregister* as "could not be registered", and — since the count it quoted was always the size
     * of `agent_task` — an empty table printed "1 of 0 active tasks could not be registered".
     */
    private fun describeDrift(
        failedApply: List<Long>,
        failedRemoval: List<Long>,
        total: Int,
    ): String? {
        val parts = listOfNotNull(
            if (failedApply.isEmpty()) {
                null
            } else {
                "${failedApply.size} of $total active tasks could not be registered: ids=[${idsOf(failedApply)}]"
            },
            if (failedRemoval.isEmpty()) {
                null
            } else {
                val noun = if (failedRemoval.size == 1) "job" else "jobs"
                "${failedRemoval.size} scheduled $noun the table no longer wants could not be unregistered: " +
                    "ids=[${idsOf(failedRemoval)}]"
            },
        )
        if (parts.isEmpty()) {
            return null
        }
        val message = parts.joinToString("; ")
        log.error("Reconcile left drift: {}", message)
        return message
    }

    /** Bounded: a table full of unusable crons must not turn a health detail into a megabyte. */
    private fun idsOf(failedIds: List<Long>): String {
        val shown = failedIds.take(MAX_DRIFT_IDS).joinToString(",")
        return if (failedIds.size > MAX_DRIFT_IDS) "$shown,+${failedIds.size - MAX_DRIFT_IDS} more" else shown
    }

    /**
     * The round's shape, which is what an operator reads after a restart: a diff that found nothing to do
     * is a debug line (the sweep runs every minute), anything that moved is loud enough to grep for.
     */
    private fun logRound(
        added: Int,
        removed: Int,
        updated: Int,
        unchanged: Int,
        scheduled: Int,
    ) {
        val message =
            "Reconciled the Quartz store with agent_task: +{} -{} ~{} unchanged={} ({} scheduled)"
        if (added + removed + updated > 0) {
            log.info(message, added, removed, updated, unchanged, scheduled)
        } else {
            log.debug(message, added, removed, updated, unchanged, scheduled)
        }
    }

    companion object {
        private const val MAX_DRIFT_IDS = 20
    }
}
