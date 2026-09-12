package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
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

    override fun save(taskLog: AgentTaskLog): Boolean {
        log.info("Saving agent task log, taskId: {}, status: {}", taskLog.taskId, taskLog.status)
        return agentTaskLogMapper.insert(taskLog) > 0
    }

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
        // it, and this is the only place that learns who is asking.
        // The tenant is forwarded as it is (null when the request carried no X-Tenant-ID) rather than
        // defaulted to 1: agent_task rows are stamped `TenantContext.getTenantId() ?: 1` on create, so a
        // fabricated id would have hidden an owner's own logs behind a tenant they never chose. The
        // creator/public rule is what closes the cross-owner read; this only narrows it further.
        return Page.fromPageInfo(
            agentTaskLogMapper.selectLogList(
                taskId,
                taskName,
                status,
                startTimeFrom,
                startTimeTo,
                keyword,
                UserContextUtil.getCurrentUsername(jwtUtil),
                TenantContext.getTenantId(),
            ),
        )
    }

    override fun getLogsByTaskId(taskId: Long): List<AgentTaskLog> = agentTaskLogMapper.selectByTaskId(taskId)

    override fun convertToResponse(taskLog: AgentTaskLog): AgentTaskLogResponse = AgentTaskLogResponse.fromEntity(taskLog)
}
