package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTaskLog
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Every write on an execution log carries a status guard in its WHERE clause. Several scheduler
 * nodes and the stop path can touch the same row, so the guard — not the caller — decides who wins:
 * 3 (running) -> {0, 1, 2, 5} and 4 (stopping) -> {5, 2}. A late writer can never resurrect a row
 * that already reached a terminal status.
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
     * able to probe which ids belong to other users. [tenantId] narrows the same optional way as
     * [selectLogList] (null = "the request carried no tenant", not "tenant 1").
     */
    fun selectVisibleById(
        @Param("id") id: Long,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long? = null,
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
     * Batch-expire executions that outran their own task's timeout, per row: -> 2.
     * Reclaims rows left behind by a node that died mid-execution.
     */
    fun expireStale(@Param("defaultTimeoutSeconds") defaultTimeoutSeconds: Int): Int

    /**
     * Paged log search, gated by the *task* the log belongs to: a row is only readable through a task
     * the caller can see (`is_public = 1 OR creator = currentUsername`, task still active), the same
     * rule as `AgentTaskMapper.selectTaskList`. Logs carry prompt/response/error_info, so reading them
     * without that join exposes another user's task traffic verbatim.
     *
     * [currentUsername] has no default on purpose: the visibility rule fails closed to "public rows
     * only" when it is missing, which is not a state any user-facing caller should be able to reach by
     * accident. [tenantId] narrows further when the caller knows the tenant (nullable, like the other
     * list queries in this module).
     */
    fun selectLogList(
        @Param("taskId") taskId: Long?,
        @Param("taskName") taskName: String?,
        @Param("status") status: Int?,
        @Param("startTimeFrom") startTimeFrom: String?,
        @Param("startTimeTo") startTimeTo: String?,
        @Param("keyword") keyword: String?,
        @Param("currentUsername") currentUsername: String,
        @Param("tenantId") tenantId: Long? = null,
    ): List<AgentTaskLog>

    fun selectByTaskId(@Param("taskId") taskId: Long): List<AgentTaskLog>

    fun selectRunningByTaskId(@Param("taskId") taskId: Long): List<AgentTaskLog>
}
