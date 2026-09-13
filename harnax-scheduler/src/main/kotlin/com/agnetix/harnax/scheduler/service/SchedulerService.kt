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
     * Whether this task already has an execution live *right now*.
     *
     * The read is keyed by task and cheap when nothing is running: an empty result is the answer, and no
     * table-wide reclaim runs for it. Only a row that is actually there gets judged against its own
     * timeout, and that reclaim is rate limited per node — it is a scan-type UPDATE competing with the
     * inserts of the executions starting right now. A dead node therefore costs at most one window of
     * refusal, not a forever-blocked task; the unbounded reclaim is housekeeping's.
     *
     * The cluster lock cannot answer this question: `agent_task_execution` is keyed by
     * (task id, trigger time), so it only ever dedupes one fire across instances and says nothing about
     * an earlier fire of the same task still running. A Quartz fire asks before it starts work.
     */
    fun hasActiveRunningExecution(taskId: Long): Boolean

    /**
     * Reclaim execution log rows that outran their own task's timeout: the rows a node leaves behind
     * when it dies mid-task, which otherwise read as "still running" forever.
     *
     * @return how many rows were reclaimed; 0 both for "nothing was stale" and for "the sweep failed",
     *   which it reports by log line rather than by throwing — a housekeeping sweep that dies on one
     *   statement would take the rest of them down with it.
     */
    fun expireStaleExecutions(): Int

    /**
     * Drop execution logs older than [retentionDays], terminal rows only. The log table is otherwise
     * append-only, and every row carries the prompt and the full agent response.
     *
     * @return how many rows were dropped, 0 when the sweep failed for the same reason as above
     */
    fun cleanupOldExecutionLogs(retentionDays: Int): Int

    /**
     * Manually trigger a task execution (bypassing Quartz scheduling).
     * Executes asynchronously and returns immediately.
     *
     * Bypassing Quartz also means bypassing everything Quartz protects: the graceful-shutdown wait and
     * the container's `stop_grace_period` cover the cron path, not this thread, so a restart mid-run
     * leaves this execution's row at 3 and its lock row at 0.
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
