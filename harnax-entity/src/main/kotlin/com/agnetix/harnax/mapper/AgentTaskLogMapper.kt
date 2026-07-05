package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTaskLog
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentTaskLogMapper {

    fun selectById(@Param("id") id: Long): AgentTaskLog?

    fun insert(log: AgentTaskLog): Int

    fun updateById(log: AgentTaskLog): Int

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
