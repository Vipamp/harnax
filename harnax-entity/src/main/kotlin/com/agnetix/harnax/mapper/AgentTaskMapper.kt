package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.AgentTask
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface AgentTaskMapper {

    /**
     * Unscoped lookup for service-to-service paths (scheduler engine, internal API) that have no
     * end-user context. Anything reachable by a logged-in user must use [selectById] instead.
     */
    fun selectAnyById(@Param("id") id: Long): AgentTask?

    /** User-facing lookup; same visibility rule as [selectTaskList]. */
    fun selectById(
        @Param("id") id: Long,
        @Param("currentUsername") currentUsername: String,
    ): AgentTask?

    fun insert(task: AgentTask): Int

    /** Owner-only: a public task is visible to everyone but editable only by its creator. */
    fun updateById(
        @Param("task") task: AgentTask,
        @Param("currentUsername") currentUsername: String,
    ): Int

    fun deleteById(
        @Param("id") id: Long,
        @Param("currentUsername") currentUsername: String,
    ): Int

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
