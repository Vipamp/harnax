package com.agnetix.harnax.scheduler.controller

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.scheduler.dto.AgentTaskCreateRequest
import com.agnetix.harnax.scheduler.dto.AgentTaskResponse
import com.agnetix.harnax.scheduler.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.scheduler.dto.Page
import com.agnetix.harnax.scheduler.entity.AgentTask
import com.agnetix.harnax.scheduler.entity.AgentTaskLog
import com.agnetix.harnax.scheduler.service.AgentTaskCrudService
import com.agnetix.harnax.scheduler.service.AgentTaskLogQueryService
import com.agnetix.harnax.scheduler.support.SchedulerBizException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The task-management surface: the eleven endpoints `harnax-admin`'s `AgentTaskController` answered out of
 * its own `agent_task` tables until release 2 moved that domain here. Paths under
 * `/api/scheduler/agent-tasks` mirror admin's under `/api/admin/agent-tasks` one for one, and the response
 * bodies — `ResultVo` shell, the seven keys of `Page`, the eighteen fields of a task record, the business
 * codes and every refusal message — are what admin produced, because the webui, the mini-program and the CLI
 * all parse them and none of them is being asked to change.
 *
 * Two of admin's endpoints are deliberately not here:
 *
 *  - `GET /agents`, which lists the caller's agents. That is admin's own domain, and it stays there.
 *  - `GET /{id}/owner`, already served by [AgentTaskOwnerController] for contract C5. It answers a different
 *    question (whose is this, for a service with no user) from a different controller, and the two path
 *    patterns do not overlap.
 *
 * Every call here arrives through [com.agnetix.harnax.scheduler.support.InternalCallerInterceptor], so the
 * "current user" of the moved rules is [com.agnetix.harnax.scheduler.support.CallerContext.username] — the
 * person admin authenticated and named in `X-Forwarded-User`.
 *
 * The five scheduling verbs (`toggle`, `start`, `pause`, `trigger`, `stop`) go through [SchedulerController]
 * instead of repeating its calls to the service. That controller is the canonical implementation of "what
 * this instance answers to a scheduling write" — the `scheduler.enabled` gate that refuses a job on an inert
 * node included, and the 40901 conflict a trigger hits when an execution is live — and admin relayed its
 * answer verbatim rather than composing its own. Duplicating those strings on a second path would give the
 * same request two answers that can drift, which is worse than the one-line relay this is.
 */
@Tag(name = "Agent Task Management", description = "Scheduled agent task management APIs")
@RestController
@RequestMapping("/api/scheduler/agent-tasks")
class AgentTaskController(
    private val agentTaskCrudService: AgentTaskCrudService,
    private val agentTaskLogQueryService: AgentTaskLogQueryService,
    private val schedulerController: SchedulerController,
) {

    private val log = LoggerFactory.getLogger(AgentTaskController::class.java)

    @Operation(summary = "Paginated agent task list")
    @GetMapping("/page")
    fun page(
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) agentId: Long?,
        @RequestParam(required = false) taskStatus: Int?,
        @RequestParam(defaultValue = "1") pageNum: Int,
        @RequestParam(defaultValue = "10") pageSize: Int,
    ): ResultVo<Page<AgentTask>> = try {
        ResultVo.success(agentTaskCrudService.page(name, agentId, taskStatus, pageNum, pageSize))
    } catch (e: Exception) {
        log.error("Failed to query agent task list", e)
        ResultVo.error("Failed to query agent task list: ${e.message}")
    }

    @Operation(summary = "Get agent task by ID")
    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ResultVo<AgentTaskResponse?> = try {
        val task = agentTaskCrudService.getAgentTask(id)
            ?: return ResultVo.error("Agent task not found")
        ResultVo.success(agentTaskCrudService.convertToResponse(task))
    } catch (e: Exception) {
        log.error("Failed to get agent task", e)
        ResultVo.error("Failed to get agent task: ${e.message}")
    }

    @Operation(summary = "Create agent task")
    @PostMapping
    fun create(@Valid @RequestBody request: AgentTaskCreateRequest): ResultVo<Void> = try {
        val success = agentTaskCrudService.createAgentTask(request)
        if (success) {
            ResultVo.success()
        } else {
            ResultVo.error("Create failed")
        }
    } catch (e: Exception) {
        log.error("Failed to create agent task", e)
        ResultVo.error("Failed to create agent task: ${e.message}")
    }

    @Operation(summary = "Update agent task")
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: Long,
        @Valid @RequestBody request: AgentTaskUpdateRequest,
    ): ResultVo<Void> = try {
        val success = agentTaskCrudService.updateAgentTask(id, request)
        if (success) {
            ResultVo.success()
        } else {
            ResultVo.error("Update failed")
        }
    } catch (e: SchedulerBizException) {
        // Let the business code through: the generic handler below flattens everything to 500, and 40902
        // ("saved, but nothing reloaded") has to stay distinguishable from "not saved".
        log.warn("Failed to update agent task: id={}, code={}, message={}", id, e.code, e.message)
        ResultVo.error(e.code, e.message ?: "Update failed")
    } catch (e: Exception) {
        log.error("Failed to update agent task", e)
        ResultVo.error("Failed to update agent task: ${e.message}")
    }

    @Operation(summary = "Delete agent task")
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResultVo<Void> = try {
        val success = agentTaskCrudService.deleteAgentTask(id)
        if (success) {
            ResultVo.success()
        } else {
            ResultVo.error("Delete failed")
        }
    } catch (e: SchedulerBizException) {
        log.warn("Failed to delete agent task: id={}, code={}, message={}", id, e.code, e.message)
        ResultVo.error(e.code, e.message ?: "Delete failed")
    } catch (e: Exception) {
        log.error("Failed to delete agent task", e)
        ResultVo.error("Failed to delete agent task: ${e.message}")
    }

    // ========================================
    // Scheduling endpoints
    // ========================================

    @Operation(summary = "Toggle task status (enable/disable)")
    @PostMapping("/toggle/{id}")
    fun toggle(
        @PathVariable id: Long,
        @RequestParam status: Int,
    ): ResultVo<Void> = try {
        // The visibility read first, exactly where admin's proxy did it: `start`/`pause` themselves have no
        // owner check (F13), so this is the only thing between an id and a schedule change.
        agentTaskCrudService.requireVisibleTask(id)
        val result = if (status == 1) schedulerController.start(id) else schedulerController.pause(id)
        if (result.isSuccess()) {
            ResultVo.success()
        } else {
            // Forward the scheduler's own reason. Judging a cron expression needs Quartz, and this is the
            // only party that can say *why* a start failed — answering `false` here used to flatten
            // "CronExpression '0 0 0 * * *' is invalid" to a bare "Failed to toggle task status".
            ResultVo.error(result.code, result.message)
        }
    } catch (e: SchedulerBizException) {
        // Business code through unchanged, same rule as update/delete: `toggle` is what the UI's status
        // switch calls, and flattening 40903 ("scheduling is disabled on this instance") into a 500 left the
        // operator with nothing to act on.
        log.warn("Failed to toggle task status: id={}, code={}, message={}", id, e.code, e.message)
        ResultVo.error(e.code, e.message ?: "Failed to toggle task status")
    } catch (e: Exception) {
        log.error("Failed to toggle task status", e)
        ResultVo.error(e.message ?: "Failed to toggle task status")
    }

    @Operation(summary = "Start a scheduled task")
    @PostMapping("/{id}/start")
    fun start(@PathVariable id: Long): ResultVo<Void> = relay(schedulerController.start(id))

    @Operation(summary = "Pause a scheduled task")
    @PostMapping("/{id}/pause")
    fun pause(@PathVariable id: Long): ResultVo<Void> = relay(schedulerController.pause(id))

    @Operation(summary = "Manually trigger a one-time task execution")
    @PostMapping("/{id}/trigger")
    fun trigger(@PathVariable id: Long): ResultVo<Void> = relay(schedulerController.trigger(id))

    /**
     * A stop is a write against somebody else's running execution, so the log id alone must not be enough:
     * it leaks easily (the log table on screen, URLs, exports). [AgentTaskCrudService.requireOwnedLog] is the
     * creator-only gate, and it runs before anything is handed to the scheduler's stop path.
     *
     * No try/catch, because admin's endpoint had none either: its `BizException` went to the global handler
     * and answered there. [com.agnetix.harnax.scheduler.config.SchedulerWebConfig] is this service's copy of
     * that landing point, so "Agent task log not found" stays a code-400 body rather than turning into a 500
     * on the way.
     */
    @Operation(summary = "Stop a running task execution")
    @PostMapping("/logs/{logId}/stop")
    fun stopTask(@PathVariable logId: Long): ResultVo<Void> {
        agentTaskCrudService.requireOwnedLog(logId)
        return relay(schedulerController.stopTask(logId))
    }

    @Operation(summary = "Get agent task execution logs")
    @GetMapping("/{id}/logs")
    fun logs(
        @PathVariable id: Long,
        @RequestParam(required = false) taskName: String?,
        @RequestParam(required = false) status: Int?,
        @RequestParam(required = false) startTimeFrom: String?,
        @RequestParam(required = false) startTimeTo: String?,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(defaultValue = "1") pageNum: Int,
        @RequestParam(defaultValue = "10") pageSize: Int,
    ): ResultVo<Page<AgentTaskLog>> = try {
        ResultVo.success(
            agentTaskLogQueryService.page(id, taskName, status, startTimeFrom, startTimeTo, keyword, pageNum, pageSize),
        )
    } catch (e: Exception) {
        log.error("Failed to query agent task logs", e)
        ResultVo.error("Failed to query agent task logs: ${e.message}")
    }

    /**
     * The scheduling answer, in the shell admin's proxy handed to the client: its own type parameter was
     * `Void`, so the human-readable string this service puts in `data` never reached a task-API caller and
     * must not start to now — a client that switched on `code` would be reading a body shape it never saw.
     */
    private fun relay(result: ResultVo<String>): ResultVo<Void> = ResultVo(result.code, result.message, null)
}
