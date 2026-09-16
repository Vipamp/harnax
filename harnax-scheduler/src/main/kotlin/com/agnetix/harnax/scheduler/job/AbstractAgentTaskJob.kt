package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.SchedulerService
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One fire of one agent task.
 *
 * Quartz instantiates jobs itself, so the collaborators come out of the scheduler context rather than
 * from injection.
 *
 * What a fire runs on is re-read from `agent_task` every time (see [taskToRun]): the stored job carries
 * only its id, so the row is also the only place the current prompt, cron and status live. Whether this
 * node may run anything at all is answered before that read, off the fire itself — a shared store can hand
 * this node a job it never registered, and refusing it must not cost a read that doubles as a store write.
 *
 * Execution is deliberately synchronous, and that is the whole point of this class hierarchy: the job
 * class is the only thing that tells Quartz "this execution is live". Hand the work to a background
 * thread and `execute()` returns immediately, Quartz files the fire as completed and removes its
 * `QRTZ_FIRED_TRIGGERS` row — after which fail-over has nothing to take over,
 * `waitForJobsToCompleteOnShutdown` has nothing to wait for, and `@DisallowConcurrentExecution` has no
 * running execution to keep a second fire away from.
 *
 * Stopping a run is not handled here either: no subclass is an `InterruptableJob`, because the real
 * interrupt is an INTERRUPT command to the router (see `SchedulerService.stopTask`).
 */
abstract class AbstractAgentTaskJob {

    private val log = LoggerFactory.getLogger(javaClass)

    protected fun schedulerService(context: JobExecutionContext): SchedulerService = context.scheduler.context["schedulerService"] as SchedulerService

    protected fun executionGuard(context: JobExecutionContext): AgentTaskExecutionGuard = context.scheduler.context["executionGuard"] as AgentTaskExecutionGuard

    protected fun run(context: JobExecutionContext) {
        val taskId = fireTaskId(context) ?: return

        // A shared store hands this node a fire for a job it never registered. On an instance with
        // scheduler.enabled=false it takes an explicit SCHEDULER_QUARTZ_AUTO_STARTUP=true to get here at all
        // — the yaml lets auto-startup follow the flag so a disabled node stays out of the cluster instead of
        // eating fires — and this refusal is what covers that override. Either way the row is picked up by
        // whichever node is scheduling.
        //
        // Before the read below, on purpose. The refusal costs a database round trip when it comes after
        // one, and it costs more than that: `taskToRun` deletes the job of a task whose row is gone, so a
        // disabled node that got here first would be making a scheduling write — the exact thing
        // `SchedulerController.requireEnabled` says this instance does not do, and a deletion of a job the
        // enabled node still owns.
        if (!schedulerService(context).schedulingEnabled) {
            log.info("Task {} fired on an instance with scheduling disabled, leaving it to another node", taskId)
            return
        }

        val task = taskToRun(context, taskId) ?: return

        // The cron is read off the store at fire time, so a task edited without a re-register (or a job
        // left behind by a delete that never reached the store) cannot run on stale configuration.
        //
        // `taskStatus` is exempt for a one-shot: that guard exists because a stored cron job is a lagging
        // copy of agent_task, but a one-shot's registration IS the user's current intent, made seconds ago,
        // so a task paused between the click and the fire must still run — both manual endpoints deliver that
        // one-shot, and neither of them ever asked the task whether it was still switched on. A soft-deleted
        // task is refused either way, and the group is what decides, because the JobDataMap is string-only.
        val scheduledFire = context.jobDetail.key.group == TaskQuartzRegistrar.GROUP_AGENT_TASK
        if (task.active != 1 || (scheduledFire && task.taskStatus != 1)) {
            log.info(
                "Task {} is no longer an active running task (status={}, active={}), skipping",
                task.id,
                task.taskStatus,
                task.active,
            )
            return
        }

        // Scheduled fire time, not wall clock: every node must derive the same trigger identity from one
        // fire, or the guard's unique key stops being a key.
        val triggerTime = LocalDateTime.ofInstant(context.scheduledFireTime.toInstant(), ZoneId.systemDefault())
        val service = schedulerService(context)
        val guard = executionGuard(context)

        // @DisallowConcurrentExecution is per JobDetail, and one task holds two of them: the cron
        // AgentTask_{id}@AgentTaskGroup and every click-time AgentTask_{id}_ONCE_*@AgentTaskGroup_ONCE.
        // The annotation therefore cannot see a manual run overlapping a scheduled one, and only this
        // read — keyed by task — can. Asked before the lock on purpose: a lock taken for a run that never
        // happens is a leaked row nothing executes and housekeeping only reaps after twice the timeout.
        //
        // Check-then-act by nature, so two fires that read at the same instant can both pass. Closing
        // that would need a per-task lock, which the (task_id, trigger_time) key cannot express.
        //
        // This line is also where the table-wide zombie reclaim used to sit. It is behind a per-node rate
        // limit now (`SchedulerServiceImpl.hasActiveRunningExecution`), and a fire that sees no live row
        // does not ask for it at all: the statement competes for the same `idx_status` range as the
        // running-log inserts of the executions starting right now.
        if (task.concurrent == 0 && service.hasActiveRunningExecution(task.id)) {
            log.info("Task {} already has a live execution and forbids overlap, skipping this fire", task.id)
            return
        }

        if (!guard.tryAcquireLock(task.id, triggerTime)) {
            log.info("Task {} already being executed by another instance, skipping", task.id)
            return
        }

        log.info("Starting agent task execution: id={}, name={}, agentId={}", task.id, task.name, task.agentId)
        try {
            service.executeTaskOnce(task, triggerTime)
        } catch (e: Exception) {
            // Logged with the task identity before Quartz's own worker swallows it, then rethrown: the
            // run really did fail, and hiding that here is how a whole schedule quietly stops working.
            log.error("Agent task execution failed: id={}, name={}, error={}", task.id, task.name, e.message, e)
            throw e
        }
        log.info("Agent task execution finished: id={}, name={}", task.id, task.name)
    }

    /**
     * The task this fire belongs to, or null when it must not run.
     *
     * A row that has since disappeared means the task was deleted while its job survived in the store, and
     * the job itself is the stale thing: deleting it here converges whatever left the two apart, and the
     * reconciler would do the same on its next round.
     */
    private fun taskToRun(
        context: JobExecutionContext,
        taskId: Long,
    ): AgentTask? {
        val mapper = context.scheduler.context["agentTaskMapper"] as? AgentTaskMapper
        if (mapper == null) {
            // Not a configuration anyone can pick: `SchedulerServiceImpl.init()` puts the mapper in this
            // context on every node, gated on nothing, so reaching a fire without it means the scheduler
            // handed work to this node before the context was filled — a startup-order bug, not a state
            // a task can legitimately be in.
            log.error(
                "Startup order is wrong: no agentTaskMapper in the scheduler context, so task {} cannot be " +
                    "loaded and this fire is refused",
                taskId,
            )
            return null
        }
        val task = mapper.selectAnyById(taskId)
        if (task == null) {
            log.warn("Task {} no longer exists; deleting its orphaned job from the store", taskId)
            runCatching { context.scheduler.deleteJob(context.jobDetail.key) }
                .onFailure { log.warn("Could not delete orphaned job for task {}: {}", taskId, it.message) }
        }
        return task
    }

    /**
     * The id this fire belongs to, read off the JobDataMap the store just handed this node.
     *
     * [TaskQuartzRegistrar.KEY_TASK_ID] is the only thing a stored job carries (`useProperties: true`
     * forbids anything but strings), so the row itself has to be re-read for the current prompt, cron and
     * status — but the *identity* is already here, and that is what lets the enabled guard refuse a fire
     * without touching the database.
     *
     * A job nobody registered through the registrar is a broken job, not a task to guess about.
     */
    private fun fireTaskId(context: JobExecutionContext): Long? {
        val raw = context.jobDetail.jobDataMap.getString(TaskQuartzRegistrar.KEY_TASK_ID)
        val taskId = raw?.toLongOrNull()
        if (taskId == null) {
            log.error(
                "Job {} carries no usable {} in its data map",
                context.jobDetail.key,
                TaskQuartzRegistrar.KEY_TASK_ID,
            )
        }
        return taskId
    }
}
