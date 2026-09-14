package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.entity.AgentTask
import org.quartz.CronScheduleBuilder
import org.quartz.Job
import org.quartz.JobBuilder
import org.quartz.JobDataMap
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.slf4j.LoggerFactory
import org.springframework.scheduling.quartz.SchedulerFactoryBean
import org.springframework.stereotype.Component

/**
 * The only place that writes an agent task into the Quartz store.
 *
 * Extracted from `SchedulerServiceImpl` because the reconciler has to register and unregister tasks the
 * same way the CRUD paths do, and a second copy of the job-key/cron/misfire rules is how the two drift
 * apart. It also fixes what the old shape could not: the JobDataMap used to carry the whole `AgentTask`,
 * so a job registered before an edit kept firing with the pre-edit prompt. With `useProperties: true` on
 * a JDBC store a non-string value is a hard error anyway, so the map holds a taskId and the job re-reads
 * the row (see [AbstractAgentTaskJob.run]).
 */
@Component
class TaskQuartzRegistrar(
    private val schedulerFactory: SchedulerFactoryBean,
) {

    private val log = LoggerFactory.getLogger(TaskQuartzRegistrar::class.java)

    private val scheduler: Scheduler get() = schedulerFactory.scheduler

    fun jobKeyOf(taskId: Long): JobKey = JobKey(jobName(taskId), GROUP_AGENT_TASK)

    fun triggerKeyOf(taskId: Long): TriggerKey = TriggerKey(jobName(taskId) + "_trigger", GROUP_AGENT_TASK)

    fun register(task: AgentTask) {
        // `id` is a non-null `Long` defaulting to 0 here, so 0 is what an unsaved entity looks like;
        // there is no null to requireNotNull() away.
        val taskId = requireNotNull(task.id.takeIf { it > 0L }) { "Cannot schedule an unsaved agent task" }
        val jobData = JobDataMap().apply { put(KEY_TASK_ID, taskId.toString()) }
        val jobDetail = JobBuilder.newJob(jobClassFor(task))
            .withIdentity(jobKeyOf(taskId))
            .usingJobData(jobData)
            .storeDurably()
            .build()

        val trigger = TriggerBuilder.newTrigger()
            .withIdentity(triggerKeyOf(taskId))
            .forJob(jobKeyOf(taskId))
            .withSchedule(
                CronScheduleBuilder.cronSchedule(task.cronExpression).apply {
                    if (task.concurrent == 0) {
                        withMisfireHandlingInstructionDoNothing()
                    } else {
                        withMisfireHandlingInstructionFireAndProceed()
                    }
                },
            )
            .build()

        // One store call, replace=true: the cron is validated by Quartz during the build above, and
        // scheduleJob(.., true) swaps job + trigger atomically and recovers a job row left without a
        // trigger. checkExists -> deleteJob -> scheduleJob had a window where the task was scheduled by
        // nothing while agent_task still read task_status=1, and `rescheduleJob` is not a usable
        // substitute for the swap — it answers a boxed null rather than false when the trigger key is
        // unknown.
        scheduler.scheduleJob(jobDetail, setOf(trigger), true)
        log.info("Registered agent task in the Quartz store: id={}, cron={}", taskId, task.cronExpression)
    }

    fun unregister(taskId: Long) {
        scheduler.deleteJob(jobKeyOf(taskId))
        log.info("Removed agent task from the Quartz store: id={}", taskId)
    }

    companion object {
        const val GROUP_AGENT_TASK = "AgentTaskGroup"

        /**
         * The registered class is the only channel that carries `concurrent` into Quartz:
         * `@DisallowConcurrentExecution` is read off that class by reflection and is not `@Inherited`, so the
         * plain class means "overlap allowed" and nothing else. Misfire instructions are not a substitute —
         * they decide what happens to a *late* fire, never whether two live ones may overlap.
         *
         * On the companion, because three callers have to answer it the same way: [register], the one-shot
         * job `SchedulerServiceImpl.runTaskOnce` builds, and `TaskScheduleReconciler` — which compares it
         * against the class already in the store, so a second copy of this decision would have the diff see
         * a change that is not one and rewrite every job on every round.
         */
        fun jobClassFor(task: AgentTask): Class<out Job> = if (task.concurrent == 0) {
            AgentTaskNonConcurrentJob::class.java
        } else {
            AgentTaskJob::class.java
        }

        /** One-shot runs live outside [GROUP_AGENT_TASK] so reconcile never deletes a user's click. */
        const val GROUP_ONCE = "AgentTaskGroup_ONCE"

        /** The only JobDataMap key an agent-task job carries; the value is the task id as a string. */
        const val KEY_TASK_ID = "taskId"

        /**
         * The one source of the *task job* name shape (the one-shot names in [GROUP_ONCE] are the
         * service's own and nothing reads them back). [jobKeyOf] writes it and [taskIdOf] reads it back, and
         * the reconcile diff is only safe because the two cannot disagree — so neither of them spells it out.
         */
        private const val JOB_NAME_PREFIX = "AgentTask_"

        private fun jobName(taskId: Long): String = JOB_NAME_PREFIX + taskId

        /** Null for a name that is not of the `AgentTask_<id>` shape (a hand-made job, say). */
        fun taskIdOf(jobKey: JobKey): Long? = jobKey.name
            .takeIf { it.startsWith(JOB_NAME_PREFIX) }
            ?.removePrefix(JOB_NAME_PREFIX)
            ?.toLongOrNull()
    }
}
