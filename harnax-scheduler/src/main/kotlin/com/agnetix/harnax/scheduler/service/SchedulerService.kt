package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.entity.AgentTask

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
     */
    fun loadTasksToScheduler()

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
     * Manually trigger a task execution (bypassing Quartz scheduling).
     * Executes asynchronously and returns immediately.
     */
    fun triggerManually(id: Long): Boolean

    /**
     * Get the list of currently scheduled tasks.
     */
    fun getScheduledTaskIds(): Set<Long>
}
