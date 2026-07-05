package com.agnetix.harnax.scheduler.job

import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.scheduler.service.AgentTaskExecutionGuard
import com.agnetix.harnax.scheduler.service.SchedulerService
import org.quartz.InterruptableJob
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Quartz job for scheduled agent task execution.
 * Delegates actual execution to [SchedulerService.executeTaskOnce].
 * Spawns a daemon thread for each execution to avoid blocking the Quartz thread pool,
 * since task execution (router HTTP call) can take minutes.
 * Implements [InterruptableJob] for Quartz API compatibility; the real interrupt is handled via
 * [SchedulerService.stopTask] which sends an INTERRUPT command to the router.
 */
class AgentTaskJob : InterruptableJob {

    private val log = LoggerFactory.getLogger(AgentTaskJob::class.java)

    // Quartz jobs are not Spring-managed, so we get beans from the scheduler context
    private fun getSchedulerService(context: JobExecutionContext): SchedulerService = context.scheduler.context["schedulerService"] as SchedulerService

    private fun getExecutionGuard(context: JobExecutionContext): AgentTaskExecutionGuard = context.scheduler.context["executionGuard"] as AgentTaskExecutionGuard

    override fun execute(context: JobExecutionContext) {
        val task = context.jobDetail.jobDataMap["agentTask"] as? AgentTask
        if (task == null) {
            log.error("Agent task not found in job data map")
            return
        }

        // Use scheduledFireTime (not wall clock) so all instances produce the same trigger time
        val triggerTime = LocalDateTime.ofInstant(
            context.scheduledFireTime.toInstant(),
            ZoneId.systemDefault(),
        )
        val executionGuard = getExecutionGuard(context)

        // Multi-instance guard: try to acquire execution lock
        if (!executionGuard.tryAcquireLock(task.id, triggerTime)) {
            log.info("Task {} already being executed by another instance, skipping", task.id)
            return
        }

        log.info("Starting agent task execution: id={}, name={}, agentId={}", task.id, task.name, task.agentId)

        val schedulerService = getSchedulerService(context)

        // Execute in a daemon thread to avoid blocking Quartz thread pool.
        // The executionGuard already ensures no duplicate execution across instances.
        Thread {
            try {
                schedulerService.executeTaskOnce(task, triggerTime)
            } catch (e: Exception) {
                log.error("Agent task execution failed: id={}, name={}, error={}", task.id, task.name, e.message, e)
            }
        }.apply {
            name = "quartz-task-${task.id}-$triggerTime"
            isDaemon = true
            start()
        }
    }

    /**
     * Quartz calls this method when scheduler.interrupt(jobKey) is invoked.
     * The real interrupt mechanism is handled by [SchedulerService.stopTask() which sends INTERRUPT command to the router.
     * This method is here for Quartz API compatibility.
     */
    override fun interrupt() {
        log.info("Quartz interrupt() called - handled via router INTERRUPT command to router")
    }
}
