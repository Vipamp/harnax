package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.entity.AgentTask
import java.time.LocalDateTime

interface SchedulerService {

    /**
     * Schedule a task for recurring execution based on its cron expression.
     */
    fun scheduleTask(task: AgentTask)

    /**
     * Remove a task from the scheduler.
     */
    fun unscheduleTask(task: AgentTask)

    /**
     * Load all running tasks from the database into the scheduler.
     * Returns false when the load left active tasks unregistered.
     */
    fun loadTasksToScheduler(): Boolean

    /**
     * Start scheduling a task (adds to Quartz).
     */
    fun startTask(id: Long): Boolean

    /**
     * Pause a scheduled task (removes from Quartz).
     */
    fun pauseTask(id: Long): Boolean

    /**
     * Trigger a one-time execution of a task via Quartz.
     */
    fun runTaskOnce(id: Long): Boolean

    /**
     * Execute a task once (shared logic for both Quartz and manual trigger).
     * Creates task log, registers in runningTasks, calls router, handles result/cleanup.
     * This method is synchronous and blocks until execution completes.
     */
    fun executeTaskOnce(task: AgentTask, triggerTime: LocalDateTime)

    /**
     * Manually trigger a task execution (bypassing Quartz scheduling).
     * Executes asynchronously and returns immediately.
     */
    fun triggerManually(id: Long): Boolean

    /**
     * Stop a running task execution by logId.
     * Sends INTERRUPT command to router and interrupts the executing thread.
     */
    fun stopTask(logId: Long): Boolean

    /**
     * Get the list of currently scheduled tasks.
     */
    fun getScheduledTaskIds(): Set<Long>
}
