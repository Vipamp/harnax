package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.SchedulerService
import org.quartz.DisallowConcurrentExecution
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory

/**
 * The periodic sweep of everything an execution leaves behind. Four duties, in this order:
 *
 * 1. reclaim the log rows whose node died, so a task is not held "running" forever;
 * 2. apply the log retention, the table's only removal path;
 * 3. drop guard rows past their retention;
 * 4. release lock rows whose holder never came back.
 *
 * Stale rows go first because the deletes must not race live work: a row still marked running is a row
 * [SchedulerService.hasActiveRunningExecution] answers "yes" to, and the two retention sweeps read the
 * same table.
 *
 * A Quartz job rather than a Spring `@Scheduled` method: with the in-memory job store every node sweeps
 * (all four operations are idempotent, the churn is not free), and the moment the JDBC store lands the
 * same registration becomes cluster-singleton without a line changing here.
 *
 * A sweep slower than its own period does not get a second one started: the retention DELETEs are
 * multi-row and would otherwise block each other's ranges, and a deadlock costs both sweeps.
 */
@DisallowConcurrentExecution
class SchedulerHousekeepingJob : Job {

    override fun execute(context: JobExecutionContext) {
        val schedulerContext = context.scheduler.context
        val guard = schedulerContext["executionGuard"] as? AgentTaskExecutionGuard
        val service = schedulerContext["schedulerService"] as? SchedulerService
        if (guard == null || service == null) {
            // Defensive only: `scheduler.enabled = false` no longer skips the context registration, because
            // the sweep is what reclaims the row an inert node's own stop leaves behind, and the job cannot
            // be on the clock before init() has filled this context. Quiet either way — a fire five minutes
            // from now is the retry.
            log.debug("Housekeeping has no collaborators registered on this instance, skipping")
            return
        }

        service.expireStaleExecutions()
        service.cleanupOldExecutionLogs(LOG_RETENTION_DAYS)
        guard.cleanupOldExecutions(GUARD_RETENTION_DAYS)
        guard.cleanupLeakedLocks()
    }

    companion object {
        /**
         * Deliberately outside `AgentTaskGroup`: that is the group the reconcile diff converges against
         * `agent_task`, and a job registered there that the task table does not account for is exactly what
         * it deletes. Both system sweeps live here, one literal deep.
         */
        const val GROUP = "SchedulerSystemGroup"
        const val JOB_NAME = "AgentTaskExecutionHousekeeping"

        /** Execution logs are kept this long; every row is a prompt plus a full agent response. */
        const val LOG_RETENTION_DAYS = 90

        /** Guard rows are bookkeeping, not user data, so they age out much sooner. */
        const val GUARD_RETENTION_DAYS = 7

        private val log = LoggerFactory.getLogger(SchedulerHousekeepingJob::class.java)
    }
}
