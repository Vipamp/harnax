package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.entity.AgentTask
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
        val task = context.jobDetail.jobDataMap["agentTask"] as? AgentTask
        if (task == null) {
            log.error("Agent task not found in job data map")
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
}
