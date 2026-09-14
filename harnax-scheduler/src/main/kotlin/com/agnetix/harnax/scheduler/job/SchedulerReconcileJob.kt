package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
import org.quartz.DisallowConcurrentExecution
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory

/**
 * Periodic convergence of the Quartz store with `agent_task`.
 *
 * `@DisallowConcurrentExecution` because a round that outruns its 60-second period would otherwise stack,
 * and two reconciles reading the same diff can each decide to register the same job — harmless under
 * replace=true, but a second one deleting while the first is adding is a fight over the store.
 */
@DisallowConcurrentExecution
class SchedulerReconcileJob : Job {

    override fun execute(context: JobExecutionContext) {
        val reconciler = context.scheduler.context["taskScheduleReconciler"] as? TaskScheduleReconciler
        if (reconciler == null) {
            log.debug("No reconciler registered in the scheduler context yet, skipping")
            return
        }
        val report = reconciler.reconcile()
        if (!report.converged) {
            log.warn("Scheduled reconcile left drift: {}", report.failedIds)
        }
    }

    companion object {
        /**
         * Same system group as the housekeeping sweep, and one literal for both: `AgentTaskGroup` is the
         * group reconcile *edits*, so a sweep registered there would be deleted by its own next round.
         */
        const val GROUP = SchedulerHousekeepingJob.GROUP
        const val JOB_NAME = "AgentTaskScheduleReconcile"

        private val log = LoggerFactory.getLogger(SchedulerReconcileJob::class.java)
    }
}
