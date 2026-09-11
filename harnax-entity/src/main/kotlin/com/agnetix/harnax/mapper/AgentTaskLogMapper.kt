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

    fun selectById(@Param("id") id: Long): AgentTaskLog?

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

    fun selectLogList(
        @Param("taskId") taskId: Long?,
        @Param("taskName") taskName: String?,
        @Param("status") status: Int?,
        @Param("startTimeFrom") startTimeFrom: String?,
        @Param("startTimeTo") startTimeTo: String?,
        @Param("keyword") keyword: String?,
    ): List<AgentTaskLog>

    fun selectByTaskId(@Param("taskId") taskId: Long): List<AgentTaskLog>

    fun selectRunningByTaskId(@Param("taskId") taskId: Long): List<AgentTaskLog>
}
