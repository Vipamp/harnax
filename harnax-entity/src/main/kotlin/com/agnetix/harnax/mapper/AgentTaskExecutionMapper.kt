package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTaskExecution
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface AgentTaskExecutionMapper {

    fun insert(execution: AgentTaskExecution): Int

    fun updateStatus(
        @Param("id") id: Long,
        @Param("status") status: Int,
        @Param("startTime") startTime: LocalDateTime?,
        @Param("endTime") endTime: LocalDateTime?,
    ): Int

    fun selectByTaskIdAndTriggerTime(
        @Param("taskId") taskId: Long,
        @Param("triggerTime") triggerTime: LocalDateTime,
    ): AgentTaskExecution?

    fun deleteOldExecutions(@Param("beforeTime") beforeTime: LocalDateTime): Int
}
