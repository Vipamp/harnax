package com.agnetix.harnax.admin.service

import com.agnetix.harnax.admin.dto.AgentTaskLogResponse
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.entity.AgentTaskLog

/**
 * Reads of `agent_task_log`. [page] is the only collection read there is, and it is gated through the
 * owning task: a log row repeats that task's prompt, response and error verbatim, so it is only readable
 * by someone who may see the task. There is no read-by-task-id beside it to bypass that rule.
 */
interface AgentTaskLogService {

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

    fun convertToResponse(log: AgentTaskLog): AgentTaskLogResponse
}
