package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTask
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentTaskMapper {

    fun selectById(@Param("id") id: Long): AgentTask?

    fun insert(task: AgentTask): Int

    fun updateById(task: AgentTask): Int

    fun deleteById(@Param("id") id: Long): Int

    fun selectTaskList(
        @Param("name") name: String?,
        @Param("agentId") agentId: Long?,
        @Param("taskStatus") taskStatus: Int?,
        @Param("currentUsername") currentUsername: String?,
    ): List<AgentTask>

    fun selectByName(@Param("name") name: String): AgentTask?

    fun selectRunningTasks(): List<AgentTask>

    fun updateStatus(@Param("id") id: Long, @Param("taskStatus") taskStatus: Int): Int
}
