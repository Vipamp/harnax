package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTaskLog
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/**
 * Every write on an execution log carries a status guard in its WHERE clause. Several scheduler
 * nodes and the stop path can touch the same row, so the guard — not the caller — decides who wins:
 * 3 (running) -> {0, 1, 4, 2}, 4 (stopping) -> {5, 2}, and 2 (expired) -> {0, 1} only through
 * [reclaimExpired], which is the one documented exception below. A late writer can never resurrect a
 * status the user or the stop path put there on purpose.
 */
@Mapper
interface AgentTaskLogMapper {

    /**
     * Lookup by log id with no owner or tenant condition — the scheduler resolves a stop request to the
     * row it has to close out and has no end-user context. Not a user-facing read: unlike
     * [selectLogList] it cannot tell whose log it returns.
     */
    fun selectById(@Param("id") id: Long): AgentTaskLog?

    /**
     * Single-row read with the same task-visibility gate as [selectLogList]: a log is only reachable
     * through a task the caller may see. This is the counterpart [selectById] does not have, and the
     * stop path needs it — without a gated read, knowing a log id was enough to interrupt somebody
     * else's running execution.
     *
     * Deliberately answers null for a row that exists but is not the caller's: the caller must not be
     * able to probe which ids belong to other users. The gate is the caller's username alone, with no
     * tenant argument — same width as the task reads this mirrors; see [selectLogList].
     */
    fun selectVisibleById(
        @Param("id") id: Long,
        @Param("currentUsername") currentUsername: String,
    ): AgentTaskLog?

    fun insert(log: AgentTaskLog): Int

    /**
     * Claim a running execution for stopping: 3 -> 4.
     * Returns 0 when the row is no longer running.
     */
    fun markStopping(
        @Param("id") id: Long,
        @Param("errorInfo") errorInfo: String?,
    ): Int

    /**
     * Write back a finished execution, only while the row is still 3.
     * Returns 0 when a stop was requested meanwhile.
     */
    fun finishExecution(log: AgentTaskLog): Int

    /** Close out an execution whose stop was requested: 4 -> 5. */
    fun finalizeStopped(log: AgentTaskLog): Int

    /**
     * Take back a row the reaper had judged [AgentTaskLog] status 2 (timeout) and write the real
     * outcome over it. Only the execution thread that actually produced a result calls this, and no
     * scheduler path writes status 2 but [expireStale] — so a row still at 2 means the reaper was
     * wrong, and the real result is the more truthful record. This is the single allowed exception to
     * the terminal-status rule in the class comment: it never touches 4 or 5, and a row already at 0
     * or 1 has a result of its own.
     */
    fun reclaimExpired(log: AgentTaskLog): Int

    /**
     * Batch-expire executions that outran their own task's timeout, per row: -> 2.
     * Reclaims rows left behind by a node that died mid-execution. The deadline is 1.5x the task
     * timeout, not the timeout itself: an execution still queued inside agent-service is not dead,
     * and reclaiming at exactly the timeout used to expire work that was merely slow.
     */
    fun expireStale(@Param("defaultTimeoutSeconds") defaultTimeoutSeconds: Int): Int

    /**
     * Retention sweep: delete execution logs created before [beforeTime]. Terminal rows only — a row
     * still at 3 (running) or 4 (stopping) is either live or has an outcome the stop path still owes
     * the user, and deleting it would throw the record away rather than age it out.
     *
     * Called by the scheduler's housekeeping job. Nothing else in this table ever removes a row, so
     * without it prompt/response/error_info grow per run forever.
     */
    fun deleteOldLogs(@Param("beforeTime") beforeTime: LocalDateTime): Int

    /**
     * Paged log search, gated by the *task* the log belongs to: a row is only readable through a task
     * the caller can see (`is_public = 1 OR creator = currentUsername`, task still active), the same
     * rule as `AgentTaskMapper.selectTaskList`. Logs carry prompt/response/error_info, so reading them
     * without that join exposes another user's task traffic verbatim.
     *
     * [currentUsername] has no default on purpose: the visibility rule fails closed to "public rows
     * only" when it is missing, which is not a state any user-facing caller should be able to reach by
     * accident. That rule is the whole gate and carries no tenant argument on purpose — `tenant_id` is
     * a creation-time snapshot, the automatic tenant interceptor in this repository is a no-op, and the
     * task reads have no tenant condition either. Filtering logs by tenant would therefore be stricter
     * than the task list: a task stays listed while its own logs go empty as soon as the owner switches
     * tenant, and the stop path then rejects their own execution.
     */
    fun selectLogList(
        @Param("taskId") taskId: Long?,
        @Param("taskName") taskName: String?,
        @Param("status") status: Int?,
        @Param("startTimeFrom") startTimeFrom: String?,
        @Param("startTimeTo") startTimeTo: String?,
        @Param("keyword") keyword: String?,
        @Param("currentUsername") currentUsername: String,
    ): List<AgentTaskLog>

    fun selectByTaskId(@Param("taskId") taskId: Long): List<AgentTaskLog>

    fun selectRunningByTaskId(@Param("taskId") taskId: Long): List<AgentTaskLog>
}
