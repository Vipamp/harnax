package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.scheduler.dto.AgentTaskLogResponse
import com.agnetix.harnax.scheduler.dto.Page
import com.agnetix.harnax.scheduler.entity.AgentTaskLog
import com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper
import com.agnetix.harnax.scheduler.service.AgentTaskLogQueryService
import com.agnetix.harnax.scheduler.support.CallerContext
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The paged execution-log read, moved with the rest of this domain from `harnax-admin`'s
 * `AgentTaskLogServiceImpl`. Same page clamps, same seven mapper arguments, same single gate.
 */
@Service
class AgentTaskLogQueryServiceImpl(
    private val agentTaskLogMapper: AgentTaskLogMapper,
) : AgentTaskLogQueryService {

    private val log = LoggerFactory.getLogger(AgentTaskLogQueryServiceImpl::class.java)

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
                requireUsername(),
            ),
        )
    }

    override fun convertToResponse(taskLog: AgentTaskLog): AgentTaskLogResponse = AgentTaskLogResponse.fromEntity(taskLog)

    /**
     * The caller's username, and the reason this read cannot be served by an unauthenticated call: the
     * visibility join fails closed to "public rows only" without it, which would answer a user's own history
     * as empty rather than as refused. `selectLogList` declares the argument as non-null for the same reason.
     */
    private fun requireUsername(): String = CallerContext.username
        ?: throw RuntimeException("Not logged in")
}
