package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.AgentTaskCreateRequest
import com.agnetix.harnax.admin.dto.AgentTaskResponse
import com.agnetix.harnax.admin.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.AgentTask

interface AgentTaskService {

    fun page(
        name: String?,
        agentId: Long?,
        taskStatus: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<AgentTask>

    fun getAgentTask(id: Long): AgentTask?

    fun createAgentTask(request: AgentTaskCreateRequest): Boolean

    fun updateAgentTask(id: Long, request: AgentTaskUpdateRequest): Boolean

    fun deleteAgentTask(id: Long): Boolean

    fun startTask(id: Long): Boolean

    fun pauseTask(id: Long): Boolean

    fun runTaskOnce(id: Long): Boolean

    fun loadTasksToScheduler()

    fun getRunningTasks(): List<AgentTask>

    fun convertToResponse(task: AgentTask): AgentTaskResponse
}
