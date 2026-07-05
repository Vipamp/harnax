package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.AgentTaskCreateRequest
import com.agnetix.harnax.admin.dto.AgentTaskResponse
import com.agnetix.harnax.admin.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.common.dto.ResultVo
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

    fun convertToResponse(task: AgentTask): AgentTaskResponse

    /** Toggle task status (enable/disable) */
    fun toggleTaskStatus(id: Long, status: Int): Boolean

    /** Start a scheduled task via scheduler */
    fun startTask(id: Long): ResultVo<Void>

    /** Pause a scheduled task via scheduler */
    fun pauseTask(id: Long): ResultVo<Void>

    /** Manually trigger a one-time task execution via scheduler */
    fun triggerTask(id: Long): ResultVo<Void>

    /** Stop a running task execution via scheduler */
    fun stopTask(logId: Long): ResultVo<Void>
}
