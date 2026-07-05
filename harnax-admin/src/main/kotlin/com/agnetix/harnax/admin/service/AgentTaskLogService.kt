package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.AgentTaskLogResponse
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.AgentTaskLog

interface AgentTaskLogService {

    fun save(log: AgentTaskLog): Boolean

    fun page(
        taskId: Long?,
        taskName: String?,
        status: Int?,
        startTimeFrom: String?,
        startTimeTo: String?,
        keyword: String?,
        pageNum: Int,
        pageSize: Int,
    ): Page<AgentTaskLog>

    fun getLogsByTaskId(taskId: Long): List<AgentTaskLog>

    fun convertToResponse(log: AgentTaskLog): AgentTaskLogResponse
}
