package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.scheduler.entity.AgentTask
import java.time.LocalDateTime

interface SchedulerService {

    /**
     * Whether this instance is allowed to run scheduled work (`scheduler.enabled`).
     *
     * A job has to ask it rather than assume it: the JDBC store is shared by the whole cluster, so a fire
     * can be claimed by a node that registered nothing. The load gate cannot express that, and the fire path
     * is the only place left that can (`AbstractAgentTaskJob.run`). Reaching this answer on an instance with
     * the flag off now takes an explicit `SCHEDULER_QUARTZ_AUTO_STARTUP=true` — the yaml lets
     * `auto-startup` follow the flag, so a disabled node stays out of the cluster instead of eating fires —
     * which makes this check the override's backstop rather than the everyday path.
     */
    val schedulingEnabled: Boolean

    /**
     * Schedule a task for recurring execution based on its cron expression.
     */
    fun scheduleTask(task: AgentTask)

    /**
     * Remove a task from the scheduler.
     */
    fun unscheduleTask(task: AgentTask)

    /**
     * Converge the Quartz store with `agent_task`, by diff.
     *
     * This is what replaced "delete every job in the task group and re-register everything": on a shared
     * store that was a cluster-wide unschedule on every node start, and it reset the fire history of jobs
     * that had not changed at all. The answer is what one round did, and `converged` is the part a caller
     * has to care about — a round that could not register some active task leaves the store diverging from
     * the table, and the next sweep retries it.
     */
    fun reconcileTasks(): ReconcileReport

    /**
     * Start scheduling a task (adds to Quartz).
     */
    fun startTask(id: Long): Boolean

    /**
     * Pause a scheduled task (removes from Quartz).
     */
    fun pauseTask(id: Long): Boolean

    /**
     * Run a task once, now: the single manual entry point behind both `/tasks/{id}/trigger` and
     * `/tasks/{id}/run-once`.
     *
     * It delivers a one-shot job into the Quartz store rather than running anything itself, so the execution
     * is protected by exactly the same shutdown wait and container grace as a cron fire, and a node that dies
     * before the trigger lands hands it to a peer instead of losing the user's click. It takes no cluster
     * lock either: that is the job's decision at fire time, on whichever node claims the trigger
     * (`AbstractAgentTaskJob`).
     *
     * @return false when the task forbids overlap and one of its executions is live, which the controller
     *   reports as the 40901 conflict; the delivery never reaches the router from here.
     */
    fun runTaskOnce(id: Long): Boolean

    /**
     * The execution body of one fire — cron and manual one-shot alike — called by
     * [com.agnetix.harnax.scheduler.job.AbstractAgentTaskJob] on the Quartz worker that claimed the trigger.
     * Creates task log, calls router, handles result/cleanup.
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
     * Ask for one execution to stop, by log id: the row is claimed for stopping (3 -> 4) and the router is
     * asked to deliver INTERRUPT for its session.
     *
     * Nothing here is interrupted — no thread of this node runs the task (the Quartz job calls the router
     * synchronously, and the session lives on whichever agent-service instance owns it). What this call
     * reports is therefore what the router answered: delivered, in which case the owning execution closes
     * its own row out; an explicit miss, in which case nobody will ever report that outcome and the row is
     * settled as stopped right here; or no verdict at all, in which case the row stays at 4 and the owning
     * node's write-back or the stale sweep decides what it becomes.
     */
    fun stopTask(logId: Long): Boolean

    /**
     * Get the list of currently scheduled tasks.
     */
    fun getScheduledTaskIds(): Set<Long>
}
