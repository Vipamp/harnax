package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.AgentTaskLogResponse
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.service.AgentTaskLogService
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AgentTaskLogServiceImpl(
    private val agentTaskLogMapper: AgentTaskLogMapper,
) : AgentTaskLogService {

    private val log = LoggerFactory.getLogger(AgentTaskLogServiceImpl::class.java)

    override fun save(taskLog: AgentTaskLog): Boolean {
        log.info("Saving agent task log, taskId: {}, status: {}", taskLog.taskId, taskLog.status)
        return agentTaskLogMapper.insert(taskLog) > 0
    }

    override fun page(
        taskId: Long?,
        taskName: String?,
        status: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<AgentTaskLog> {
        log.info(
            "Paginated query for agent task log, pageNum: {}, pageSize: {}, taskId: {}, taskName: {}, status: {}",
            pageNum,
            pageSize,
            taskId,
            taskName,
            status,
        )
        PageHelper.startPage<AgentTaskLog>(pageNum, pageSize)
        return Page.fromPageInfo(agentTaskLogMapper.selectLogList(taskId, taskName, status))
    }

    override fun getLogsByTaskId(taskId: Long): List<AgentTaskLog> {
        return agentTaskLogMapper.selectByTaskId(taskId)
    }

    override fun convertToResponse(taskLog: AgentTaskLog): AgentTaskLogResponse {
        return AgentTaskLogResponse.fromEntity(taskLog)
    }
}
