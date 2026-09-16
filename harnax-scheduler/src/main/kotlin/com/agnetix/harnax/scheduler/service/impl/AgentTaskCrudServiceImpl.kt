package com.agnetix.harnax.scheduler.service.impl

import com.agnetix.harnax.scheduler.dto.AgentTaskCreateRequest
import com.agnetix.harnax.scheduler.dto.AgentTaskResponse
import com.agnetix.harnax.scheduler.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.scheduler.dto.Page
import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.entity.AgentTaskLog
import com.agnetix.harnax.scheduler.mapper.AgentTaskLogMapper
import com.agnetix.harnax.scheduler.mapper.AgentTaskMapper
import com.agnetix.harnax.scheduler.service.AgentTaskCrudService
import com.agnetix.harnax.scheduler.service.TaskScheduleReconciler
import com.agnetix.harnax.scheduler.support.CallerContext
import com.agnetix.harnax.scheduler.support.SchedulerBizException
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

/**
 * The task definitions of this domain: list, read, create, update, delete, and the two gates a write
 * surface needs before it acts on a row. Ported from `harnax-admin`'s `AgentTaskServiceImpl` — this is a
 * move, so the defaults, the refusal texts and the ordering of the checks are the ones that service had,
 * not a second opinion on them.
 *
 * Two things differ from the original, and both come from the move itself:
 *
 *  - the caller. Admin read the username out of the request JWT; this service has no user credential of its
 *    own (contract C4), so it reads [CallerContext], which [com.agnetix.harnax.scheduler.support.InternalCallerInterceptor]
 *    fills from `X-Forwarded-User`. A call with no user behind it is refused the same way admin's
 *    `UserContextUtil` refused it — with "Not logged in" — and the controllers turn that into the same body
 *    they always turned an exception into.
 *  - the reconcile. Admin could only *ask* the scheduler to converge by HTTP; here it is a local call to
 *    [TaskScheduleReconciler.reconcile], kept after the commit for the reason it was moved there in the
 *    first place (see [reconcileAfterCommit]).
 *
 * What was deliberately not added while moving: a tenant condition. `agent_task.tenant_id` is a creation-time
 * snapshot, admin's automatic tenant interceptor is a documented no-op, and neither the reads nor the writes
 * of this table carried one — so neither does this service.
 */
@Service
class AgentTaskCrudServiceImpl(
    private val agentTaskMapper: AgentTaskMapper,
    private val agentTaskLogMapper: AgentTaskLogMapper,
    private val reconciler: TaskScheduleReconciler,
) : AgentTaskCrudService {

    private val log = LoggerFactory.getLogger(AgentTaskCrudServiceImpl::class.java)

    override fun page(
        name: String?,
        agentId: Long?,
        taskStatus: Int?,
        pageNum: Int,
        pageSize: Int,
    ): Page<AgentTask> {
        val currentUsername = requireUsername()
        val safePageNum = pageNum.coerceAtLeast(1)
        val safePageSize = pageSize.coerceIn(1, 1000)
        PageHelper.startPage<AgentTask>(safePageNum, safePageSize)
        return Page.fromPageInfo(agentTaskMapper.selectTaskList(name, agentId, taskStatus, currentUsername))
    }

    override fun getAgentTask(id: Long): AgentTask? = agentTaskMapper.selectById(id, requireUsername())

    @Transactional(rollbackFor = [Exception::class])
    override fun createAgentTask(request: AgentTaskCreateRequest): Boolean {
        log.info("Creating agent task, name: {}, agentId: {}", request.name, request.agentId)

        // Check name uniqueness
        val existing = agentTaskMapper.selectByName(request.name)
        if (existing != null) {
            throw SchedulerBizException("Task name already exists")
        }

        // Validate cron expression
        if (!isValidCron(request.cronExpression)) {
            throw SchedulerBizException("Invalid cron expression")
        }

        // The agent's name is admin's data, so it arrives on the request instead of being looked up here;
        // a missing one means the caller could not resolve the id, which is what "Agent not found" says.
        val agentName = request.agentName?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw SchedulerBizException("Agent not found")

        val task = AgentTask()
        task.name = request.name
        // `agentId` is @NotNull on the DTO, so this only reaches a caller that got past bean validation —
        // the same `!!` admin's create did, and the same 500-shaped answer it produced.
        task.agentId = request.agentId!!
        task.agentName = agentName
        task.prompt = request.prompt
        task.cronExpression = request.cronExpression
        task.taskStatus = 0 // paused by default
        task.concurrent = request.concurrent
        task.timeoutSeconds = request.timeoutSeconds
        task.description = request.description
        task.isPublic = request.isPublic
        task.tenantId = CallerContext.tenantId ?: 1
        task.active = 1
        task.creator = requireUsername()
        task.createTime = LocalDateTime.now()
        task.updateTime = LocalDateTime.now()

        val success = agentTaskMapper.insert(task) > 0
        log.info("Agent task creation {}, id: {}", if (success) "successful" else "failed", task.id)
        return success
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateAgentTask(id: Long, request: AgentTaskUpdateRequest): Boolean {
        log.info("Updating agent task, id: {}", id)

        val currentUsername = requireUsername()
        val task = agentTaskMapper.selectById(id, currentUsername)
            ?: throw SchedulerBizException("Agent task not found")

        // Check name uniqueness if name changed
        if (request.name != null && request.name != task.name) {
            val existing = agentTaskMapper.selectByName(request.name)
            if (existing != null) {
                throw SchedulerBizException("Task name already exists")
            }
            task.name = request.name
        }

        // Validate cron if changed
        if (request.cronExpression != null) {
            if (!isValidCron(request.cronExpression)) {
                throw SchedulerBizException("Invalid cron expression")
            }
            task.cronExpression = request.cronExpression
        }

        // Update agent if changed
        if (request.agentId != null && request.agentId != task.agentId) {
            // Same cross-domain snapshot as on create: the caller resolved the new agent, and a request
            // that changes the agent without naming it is the "Agent not found" answer this used to throw
            // after its own lookup came back empty.
            val agentName = request.agentName?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw SchedulerBizException("Agent not found")
            task.agentId = request.agentId
            task.agentName = agentName
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
            throw SchedulerBizException("Only the task creator can modify this task")
        }

        // Reconcile the shared Quartz store (drops the old job, applies the updated config).
        // Deferred to after the commit: the reconcile reads through its own connection and cannot see
        // this row while the transaction is still open.
        reconcileAfterCommit(
            "saved",
            "The previous definition stays scheduled until a reconcile round converges it (the 60s cluster sweep runs one anyway)",
        )
        return true
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun deleteAgentTask(id: Long): Boolean {
        log.info("Deleting agent task, id: {}", id)

        val currentUsername = requireUsername()
        agentTaskMapper.selectById(id, currentUsername)
            ?: throw SchedulerBizException("Agent task not found")

        // Delete from DB first, then reconcile the shared store to remove the stale Quartz job
        if (agentTaskMapper.deleteById(id, currentUsername) == 0) {
            throw SchedulerBizException("Only the task creator can delete this task")
        }
        reconcileAfterCommit(
            "deleted",
            "Its orphaned job will not run the deleted task — it deletes itself on its next fire, or with the 60s sweep",
        )
        return true
    }

    override fun convertToResponse(task: AgentTask): AgentTaskResponse = AgentTaskResponse.fromEntity(task)

    override fun requireVisibleTask(id: Long): AgentTask = agentTaskMapper.selectById(id, requireUsername())
        ?: throw SchedulerBizException("Agent task not found")

    /**
     * The gate a stop has to pass, i.e. the one write authorisation this table has.
     *
     * [AgentTaskLogMapper.selectOwnedById] restricts the row to the **creator** of the task it belongs to.
     * That is deliberately narrower than the execution-log list read, which also shows other people's public
     * tasks: seeing a run is not the same as being allowed to interrupt it. Answering the not-found error for
     * "exists but is not yours" is on purpose — a distinct "forbidden" would turn the endpoint into an id
     * probe — and the gate is the caller's username and nothing else, no tenant.
     */
    override fun requireOwnedLog(logId: Long): AgentTaskLog = agentTaskLogMapper.selectOwnedById(logId, requireUsername())
        ?: throw SchedulerBizException("Agent task log not found")

    /**
     * Cheap structural pre-check: the field count of Quartz's cron grammar — 6 fields (second, minute,
     * hour, day-of-month, month, day-of-week) plus an optional 7th year field. Nothing more.
     *
     * Moved across at the width admin had it, including what it cannot judge: Quartz rejects expressions
     * this accepts (`0 0 0 * * *`, where day-of-month and day-of-week conflict), and the semantic authority
     * for that is still [com.agnetix.harnax.scheduler.service.SchedulerService.startTask] building the
     * trigger. Making this check real would mean validating with Quartz here, on a write path whose only job
     * is to refuse the shapes that could never be stored, and the release-2 plan does not ask for that.
     */
    private fun isValidCron(cron: String): Boolean {
        val fields = cron.trim().split("\\s+".toRegex())
        return fields.size in 6..7
    }

    /**
     * The caller this domain acts for, i.e. what admin's `UserContextUtil.getCurrentUsername(jwtUtil)` answered
     * from the request JWT.
     *
     * Refused rather than defaulted: an empty username would reach `selectTaskList` and `selectById` and
     * quietly narrow every caller's list to the public tasks, which is a different rule than the one being
     * moved. A user-facing call with no `X-Forwarded-User` is a mis-forward, and "Not logged in" is the
     * message this domain has always used for it.
     */
    private fun requireUsername(): String = CallerContext.username
        ?: throw RuntimeException("Not logged in")

    /**
     * Reconcile the Quartz store once — and only once — this transaction commits.
     *
     * Reloading inside the method body was the bug that put this here in the first place: the reconcile
     * reads `agent_task` through its own connection, so a round started before the commit registers the row
     * as it was *before* the write. Tying it to `afterCommit` also means a rolled-back write reconciles
     * nothing — the property that made admin's HTTP notify safe, and unchanged now that it is a local call.
     *
     * Without an active transaction there is nothing to wait for, so the round runs inline. That path exists
     * for callers that reach this bean without going through the transactional proxy.
     */
    private fun reconcileAfterCommit(committed: String, staleConsequence: String) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            reconcileNow(committed, staleConsequence)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    reconcileNow(committed, staleConsequence)
                }
            },
        )
    }

    /**
     * A round that leaves drift after the write is committed is a real failure, not a warning to log away:
     * the store every instance reads keeps serving the old definition until a round converges. It is reported
     * as [CODE_SCHEDULER_SYNC_FAILED] rather than as a rollback, because [committed] has already reached the
     * database by then — the caller has to learn "stored but not scheduled", which is a different fact from
     * "not stored".
     *
     * The reason text is the one admin's HTTP notify used to read off this same service's `/reload` answer,
     * so the 40902 message a client sees did not change with the call site moving in-process.
     */
    private fun reconcileNow(committed: String, staleConsequence: String) {
        val report = try {
            reconciler.reconcile()
        } catch (e: Exception) {
            log.error("Reconciling the scheduler after the task was {} threw", committed, e)
            null
        }
        if (report?.converged == true) {
            return
        }
        val reason = when {
            report != null -> "The Quartz store did not converge with agent_task, see /actuator/health for details"
            else -> "the reload call threw"
        }
        log.error("Scheduler reload after the task was {} did not succeed: {}", committed, reason)
        throw SchedulerBizException(
            CODE_SCHEDULER_SYNC_FAILED,
            "Task $committed, but the scheduler did not reload: $reason. $staleConsequence.",
        )
    }

    companion object {
        /**
         * The row is committed while no scheduler has picked the change up. Kept in the 409xx family started
         * by `SchedulerController.CODE_EXECUTION_IN_PROGRESS`: the request was honoured, but the state the
         * caller asked for is not in effect yet — distinct from a plain 500, which would read as "your edit
         * was lost".
         */
        const val CODE_SCHEDULER_SYNC_FAILED = 40902
    }
}
