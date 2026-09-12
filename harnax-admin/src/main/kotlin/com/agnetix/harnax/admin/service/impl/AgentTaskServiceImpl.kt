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
import com.agnetix.harnax.mapper.AgentTaskLogMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

@Service
class AgentTaskServiceImpl(
    private val agentTaskMapper: AgentTaskMapper,
    private val agentTaskLogMapper: AgentTaskLogMapper,
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

        // Notify all scheduler instances to reload (removes old Quartz job, applies updated config).
        // Deferred to after the commit: the scheduler reads through its own connection and cannot see
        // this row while the transaction is still open.
        reloadSchedulersAfterCommit("saved", "The previous definition stays live until a reload succeeds")
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
        reloadSchedulersAfterCommit("deleted", "The deleted task can still fire until a reload succeeds")
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
        if (result.isSuccess()) {
            return true
        }
        // Forward the scheduler's own reason. Judging a cron expression needs Quartz, which this module
        // deliberately does not depend on, so the scheduler is the only party that can say *why* a start
        // failed — answering `false` here used to flatten "CronExpression '0 0 0 * * *' is invalid" to a
        // bare "Failed to toggle task status".
        throw BizException(result.code, result.message)
    }

    override fun startTask(id: Long): ResultVo<Void> = schedulerClient.startTask(id)

    override fun pauseTask(id: Long): ResultVo<Void> = schedulerClient.pauseTask(id)

    override fun triggerTask(id: Long): ResultVo<Void> = schedulerClient.triggerTask(id)

    /**
     * A stop is a write against somebody else's running execution, so the log id alone must not be
     * enough: it leaks easily (the log table on screen, URLs, exports). The scheduler cannot make this
     * call — it has no end-user context — so the gate has to sit here, before the forward.
     *
     * [AgentTaskLogMapper.selectVisibleById] applies the same rule the execution-log list read uses: the
     * row is only reachable through a task the caller may see. Answering the not-found error for "exists
     * but is not yours" is on purpose; a distinct "forbidden" would turn this endpoint into an id probe.
     */
    override fun stopTask(logId: Long): ResultVo<Void> {
        agentTaskLogMapper.selectVisibleById(logId, UserContextUtil.getCurrentUsername(jwtUtil), TenantContext.getTenantId())
            ?: throw BizException("Agent task log not found")
        return schedulerClient.stopTask(logId)
    }

    /**
     * Cheap structural pre-check: a cron field count in 5..6. Nothing more.
     *
     * It cannot judge an expression. Quartz rejects what this accepts (`0 0 0 * * *`, where
     * day-of-month and day-of-week conflict) and accepts what this rejects (the 7-field form with a
     * year), so the authority is the scheduler: it builds the trigger through Quartz on `start` and
     * [toggleTaskStatus] forwards the reason it answers. Doing the real check here would mean pulling a
     * Quartz dependency into a module that only proxies scheduling decisions.
     */
    private fun isValidCron(cron: String): Boolean {
        val fields = cron.trim().split("\\s+".toRegex())
        return fields.size in 5..6
    }

    /**
     * Broadcast a reload to every scheduler instance once — and only once — this transaction commits.
     *
     * Broadcasting inside the method body was the bug: harnax-scheduler is a separate process with its
     * own connection pool, so it reads the row as it was *before* this transaction and re-registers the
     * old definition (for a delete, it re-registers a task that the UI already shows as gone). Tying the
     * notify to `afterCommit` also means a rolled-back write broadcasts nothing.
     *
     * Without an active transaction there is nothing to wait for, so the notify runs inline. That path
     * exists for callers that reach this bean without going through the transactional proxy.
     */
    private fun reloadSchedulersAfterCommit(committed: String, staleConsequence: String) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifySchedulersNow(committed, staleConsequence)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    notifySchedulersNow(committed, staleConsequence)
                }
            },
        )
    }

    /**
     * A reload that answers non-200 is a real failure, not a warning to log away: this instance keeps
     * firing the old definition until something reloads. It is reported as [CODE_SCHEDULER_SYNC_FAILED]
     * rather than as a rollback, because [committed] has already reached the database by then — the
     * caller has to learn "stored but not scheduled", which is a different fact from "not stored".
     */
    private fun notifySchedulersNow(committed: String, staleConsequence: String) {
        val result = try {
            schedulerClient.reloadTasks()
        } catch (e: Exception) {
            log.error("Broadcasting a scheduler reload after the task was {} threw", committed, e)
            null
        }
        if (result?.code == 200) {
            return
        }
        val reason = result?.message ?: "the broadcast threw"
        log.error("Scheduler reload after the task was {} did not succeed: {}", committed, reason)
        throw BizException(
            CODE_SCHEDULER_SYNC_FAILED,
            "Task $committed, but the scheduler did not reload: $reason. $staleConsequence.",
        )
    }

    companion object {
        /**
         * The row is committed while no scheduler has picked the change up. Kept in the 409xx family
         * started by `SchedulerController.CODE_EXECUTION_IN_PROGRESS`: the request was honoured, but
         * the state the caller asked for is not in effect yet — distinct from a plain 500, which would
         * read as "your edit was lost".
         */
        const val CODE_SCHEDULER_SYNC_FAILED = 40902
    }
}
