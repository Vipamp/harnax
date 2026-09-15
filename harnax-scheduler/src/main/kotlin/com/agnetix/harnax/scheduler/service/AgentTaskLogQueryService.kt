package com.agnetix.harnax.scheduler.service

import com.agnetix.harnax.scheduler.dto.AgentTaskLogResponse
import com.agnetix.harnax.scheduler.dto.Page
import com.agnetix.harnax.scheduler.entity.AgentTaskLog

/**
 * Reads of `agent_task_log`, moved here from `harnax-admin`'s `AgentTaskLogService` in release 2.
 *
 * [page] is the only collection read there is, and it is gated through the owning task: a log row repeats
 * that task's prompt, response and error verbatim, so it is only readable by someone who may see the task
 * ([com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper.selectLogList]). There is no read-by-task-id
 * beside it that could bypass that rule.
 */
interface AgentTaskLogQueryService {

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
