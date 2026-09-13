package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.AgentTaskLogResponse
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.service.AgentTaskLogService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.AgentTaskLog
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AgentTaskLogServiceImpl(
    private val agentTaskLogMapper: AgentTaskLogMapper,
    private val jwtUtil: JwtUtil,
) : AgentTaskLogService {

    private val log = LoggerFactory.getLogger(AgentTaskLogServiceImpl::class.java)

    override fun page(
        taskId: Long?,
        taskName: String?,
        status: Int?,
        startTimeFrom: String?,
        startTimeTo: String?,
        keyword: String?,
        pageNum: Int,
        pageSize: Int,
    ): Page<AgentTaskLog> {
        log.info(
            "Paginated query for agent task log, pageNum: {}, pageSize: {}, taskId: {}, taskName: {}, status: {}, startTimeFrom: {}, startTimeTo: {}, keyword: {}",
            pageNum,
            pageSize,
            taskId,
            taskName,
            status,
            startTimeFrom,
            startTimeTo,
            keyword,
        )
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<AgentTaskLog>(safePageNum, safePageSize)
        // A log is only readable through a task the caller may see; the join in selectLogList enforces
        // it, and this is the one place that learns who is asking. No tenant is forwarded: the gate is
        // the creator/public rule, which is exactly what the task list applies. agent_task.tenant_id is
        // only the snapshot of the tenant active at creation time, so narrowing the log read by the
        // caller's current tenant would leave a task listed while its own execution logs come back empty.
        return Page.fromPageInfo(
            agentTaskLogMapper.selectLogList(
                taskId,
                taskName,
                status,
                startTimeFrom,
                startTimeTo,
                keyword,
                UserContextUtil.getCurrentUsername(jwtUtil),
            ),
        )
    }

    override fun getLogsByTaskId(taskId: Long): List<AgentTaskLog> = agentTaskLogMapper.selectByTaskId(taskId)

    override fun convertToResponse(taskLog: AgentTaskLog): AgentTaskLogResponse = AgentTaskLogResponse.fromEntity(taskLog)
}
