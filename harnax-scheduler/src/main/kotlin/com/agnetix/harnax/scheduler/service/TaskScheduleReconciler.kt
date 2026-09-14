package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.health.QuartzJobInventory
import com.agnetix.harnax.scheduler.health.RegisteredJob
import com.agnetix.harnax.scheduler.health.SchedulerStatus
import com.agnetix.harnax.scheduler.job.TaskQuartzRegistrar
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
        val expected = agentTaskMapper.selectRunningTasks().associateBy { it.id }
        val actual = inventory.agentTaskJobs()

        var added = 0
        var removed = 0
        var updated = 0
        var unchanged = 0
        val failed = mutableListOf<Long>()

        for ((taskId, task) in expected) {
            val registered = actual[taskId]
            when {
                registered == null -> {
                    added++
                    applyDiff(failed, taskId) { registrar.register(task) }
                }

                !matches(task, registered) -> {
                    updated++
                    applyDiff(failed, taskId) { registrar.register(task) }
                }

                else -> unchanged++
            }
        }
        for (taskId in actual.keys - expected.keys) {
            removed++
            applyDiff(failed, taskId) { registrar.unregister(taskId) }
        }

        reportDrift(added, removed, updated)
        val drift = describeDrift(failed, expected.size)
        val scheduled = unchanged + updated + added - failed.count { expected.containsKey(it) }
        status.recordReconcile(scheduled, drift)
        metrics.recordLoadAttempt(success = drift == null)
        logRound(added, removed, updated, unchanged, scheduled)
        return ReconcileReport(added, removed, updated, unchanged, failed)
    }

    /** An old job that predates the concurrent flag, or a task whose flag moved, must be re-registered. */
    private fun matches(
        task: AgentTask,
        registered: RegisteredJob,
    ): Boolean = registered.cronExpression == task.cronExpression &&
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

    /** Null means the store now matches the table. The text doubles as the /actuator/health detail. */
    private fun describeDrift(
        failedIds: List<Long>,
        total: Int,
    ): String? {
        if (failedIds.isEmpty()) {
            return null
        }
        // Bounded: a table full of unusable crons must not turn a health detail into a megabyte.
        val shown = failedIds.take(MAX_DRIFT_IDS).joinToString(",")
        val hidden = if (failedIds.size > MAX_DRIFT_IDS) ",+${failedIds.size - MAX_DRIFT_IDS} more" else ""
        val message = "${failedIds.size} of $total active tasks could not be registered: ids=[$shown$hidden]"
        log.error("Reconcile left drift: {}", message)
        return message
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
