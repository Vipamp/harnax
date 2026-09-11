package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.AgentTaskCreateRequest
import com.agnetix.harnax.admin.dto.AgentTaskResponse
import com.agnetix.harnax.admin.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.service.AgentTaskService
import com.agnetix.harnax.admin.service.SchedulerClient
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class AgentTaskServiceImpl(
    private val agentTaskMapper: AgentTaskMapper,
    private val agentService: AgentService,
    private val jwtUtil: JwtUtil,
    private val schedulerClient: SchedulerClient,
) : AgentTaskService {

    private val log = LoggerFactory.getLogger(AgentTaskServiceImpl::class.java)

    override fun page(
        name: String?,
        agentId: Long?,
        taskStatus: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<AgentTask> {
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<AgentTask>(safePageNum, safePageSize)
        return Page.fromPageInfo(agentTaskMapper.selectTaskList(name, agentId, taskStatus, currentUsername))
    }

    override fun getAgentTask(id: Long): AgentTask? = agentTaskMapper.selectById(id, UserContextUtil.getCurrentUsername(jwtUtil))

    @Transactional(rollbackFor = [Exception::class])
    override fun createAgentTask(request: AgentTaskCreateRequest): Boolean {
        log.info("Creating agent task, name: {}, agentId: {}", request.name, request.agentId)

        // Check name uniqueness
        val existing = agentTaskMapper.selectByName(request.name)
        if (existing != null) {
            throw BizException("Task name already exists")
        }

        // Validate cron expression
        if (!isValidCron(request.cronExpression)) {
            throw BizException("Invalid cron expression")
        }

        // Get agent name
        val agent = agentService.getAgent(request.agentId!!)
            ?: throw BizException("Agent not found")

        val task = AgentTask()
        task.name = request.name
        task.agentId = request.agentId
        task.agentName = agent.name
        task.prompt = request.prompt
        task.cronExpression = request.cronExpression
        task.taskStatus = 0 // paused by default
        task.concurrent = request.concurrent
        task.timeoutSeconds = request.timeoutSeconds
        task.description = request.description
        task.isPublic = request.isPublic
        task.tenantId = TenantContext.getTenantId() ?: 1
        task.active = 1
        task.creator = UserContextUtil.getCurrentUsername(jwtUtil) ?: ""
        task.createTime = LocalDateTime.now()
        task.updateTime = LocalDateTime.now()

        val success = agentTaskMapper.insert(task) > 0
        log.info("Agent task creation {}, id: {}", if (success) "successful" else "failed", task.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateAgentTask(id: Long, request: AgentTaskUpdateRequest): Boolean {
        log.info("Updating agent task, id: {}", id)

        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        val task = agentTaskMapper.selectById(id, currentUsername)
            ?: throw BizException("Agent task not found")

        // Check name uniqueness if name changed
        if (request.name != null && request.name != task.name) {
            val existing = agentTaskMapper.selectByName(request.name)
            if (existing != null) {
                throw BizException("Task name already exists")
            }
            task.name = request.name
        }

        // Validate cron if changed
        if (request.cronExpression != null) {
            if (!isValidCron(request.cronExpression)) {
                throw BizException("Invalid cron expression")
            }
            task.cronExpression = request.cronExpression
        }

        // Update agent if changed
        if (request.agentId != null && request.agentId != task.agentId) {
            val agent = agentService.getAgent(request.agentId)
                ?: throw BizException("Agent not found")
            task.agentId = request.agentId
            task.agentName = agent.name
        }

        // Update other fields
        request.prompt?.let { task.prompt = it }
        request.concurrent?.let { task.concurrent = it }
        request.timeoutSeconds?.let { task.timeoutSeconds = it }
        request.description?.let { task.description = it }
        request.isPublic?.let { task.isPublic = it }

        // Reset to paused after update
        task.taskStatus = 0
        task.updateTime = LocalDateTime.now()

        val success = agentTaskMapper.updateById(task, currentUsername) > 0
        if (!success) {
            // selectById lets a public task through; rewriting it belongs to the creator alone.
            throw BizException("Only the task creator can modify this task")
        }

        // Notify all scheduler instances to reload (removes old Quartz job, applies updated config)
        try {
            schedulerClient.reloadTasks()
        } catch (e: Exception) {
            log.warn("Failed to notify scheduler after task update: {}", e.message)
        }
        return true
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteAgentTask(id: Long): Boolean {
        log.info("Deleting agent task, id: {}", id)

        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        agentTaskMapper.selectById(id, currentUsername)
            ?: throw BizException("Agent task not found")

        // Delete from DB first, then reload all scheduler instances to remove stale Quartz jobs
        if (agentTaskMapper.deleteById(id, currentUsername) == 0) {
            throw BizException("Only the task creator can delete this task")
        }
        try {
            schedulerClient.reloadTasks()
        } catch (e: Exception) {
            log.warn("Failed to reload schedulers after task deletion: {}", e.message)
        }
        return true
    }

    override fun convertToResponse(task: AgentTask): AgentTaskResponse = AgentTaskResponse.fromEntity(task)

    // ========================================
    // Scheduler Proxy Methods
    // ========================================

    override fun toggleTaskStatus(id: Long, status: Int): Boolean {
        agentTaskMapper.selectById(id, UserContextUtil.getCurrentUsername(jwtUtil))
            ?: throw BizException("Agent task not found")
        val result = if (status == 1) {
            schedulerClient.startTask(id)
        } else {
            schedulerClient.pauseTask(id)
        }
        return result.code == 200
    }

    override fun startTask(id: Long): ResultVo<Void> = schedulerClient.startTask(id)

    override fun pauseTask(id: Long): ResultVo<Void> = schedulerClient.pauseTask(id)

    override fun triggerTask(id: Long): ResultVo<Void> = schedulerClient.triggerTask(id)

    override fun stopTask(logId: Long): ResultVo<Void> = schedulerClient.stopTask(logId)

    /**
     * Simple cron expression validation (5 or 6 fields separated by spaces)
     */
    private fun isValidCron(cron: String): Boolean {
        val fields = cron.trim().split("\\s+".toRegex())
        return fields.size in 5..6
    }
}
