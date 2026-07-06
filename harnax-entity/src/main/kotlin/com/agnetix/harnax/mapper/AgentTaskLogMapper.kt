package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTaskLog
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentTaskLogMapper {

    fun selectById(@Param("id") id: Long): AgentTaskLog?

    fun insert(log: AgentTaskLog): Int

    fun updateById(log: AgentTaskLog): Int

    /**
     * Lightweight status-only update. Used by stopTask() to set status=4 (stopping)
     * immediately so the frontend gets instant feedback while the agent is being interrupted.
     */
    fun updateStatusById(@Param("id") id: Long, @Param("status") status: Int, @Param("errorInfo") errorInfo: String?): Int

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

    fun selectAllRunningLogs(): List<AgentTaskLog>
}
