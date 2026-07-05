package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.AgentTaskCreateRequest
import com.agnetix.harnax.admin.dto.AgentTaskResponse
import com.agnetix.harnax.admin.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.service.AgentTaskLogService
import com.agnetix.harnax.admin.service.AgentTaskService
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTaskLog
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@Tag(name = "Agent Task Management", description = "Scheduled agent task management APIs")
@RestController
@RequestMapping("/api/admin/agent-tasks")
class AgentTaskController(
    private val agentTaskService: AgentTaskService,
    private val agentTaskLogService: AgentTaskLogService,
    private val agentService: AgentService,
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
        ResultVo.success(agentTaskService.page(name, agentId, taskStatus, pageNum, pageSize))
    } catch (e: Exception) {
        log.error("Failed to query agent task list", e)
        ResultVo.error("Failed to query agent task list")
    }

    @Operation(summary = "Get agent task by ID")
    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ResultVo<AgentTaskResponse?> {
        return try {
            val task = agentTaskService.getAgentTask(id)
                ?: return ResultVo.error("Agent task not found")
            ResultVo.success(agentTaskService.convertToResponse(task))
        } catch (e: Exception) {
            log.error("Failed to get agent task", e)
            ResultVo.error("Failed to get agent task")
        }
    }

    @Operation(summary = "Create agent task")
    @PostMapping
    fun create(@Valid @RequestBody request: AgentTaskCreateRequest): ResultVo<Void> = try {
        val success = agentTaskService.createAgentTask(request)
        if (success) {
            ResultVo.success()
        } else {
            ResultVo.error("Create failed")
        }
    } catch (e: Exception) {
        log.error("Failed to create agent task", e)
        ResultVo.error("Failed to create agent task")
    }

    @Operation(summary = "Update agent task")
    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: AgentTaskUpdateRequest): ResultVo<Void> = try {
        val success = agentTaskService.updateAgentTask(id, request)
        if (success) {
            ResultVo.success()
        } else {
            ResultVo.error("Update failed")
        }
    } catch (e: Exception) {
        log.error("Failed to update agent task", e)
        ResultVo.error("Failed to update agent task")
    }

    @Operation(summary = "Delete agent task")
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResultVo<Void> = try {
        val success = agentTaskService.deleteAgentTask(id)
        if (success) {
            ResultVo.success()
        } else {
            ResultVo.error("Delete failed")
        }
    } catch (e: Exception) {
        log.error("Failed to delete agent task", e)
        ResultVo.error("Failed to delete agent task")
    }

    // ========================================
    // Scheduler Proxy Endpoints
    // ========================================

    @Operation(summary = "Toggle task status (enable/disable)")
    @PostMapping("/toggle/{id}")
    fun toggle(
        @PathVariable id: Long,
        @RequestParam status: Int,
    ): ResultVo<Void> = try {
        if (agentTaskService.toggleTaskStatus(id, status)) {
            ResultVo.success()
        } else {
            ResultVo.error("Failed to toggle task status")
        }
    } catch (e: Exception) {
        log.error("Failed to toggle task status", e)
        ResultVo.error(e.message ?: "Failed to toggle task status")
    }

    @Operation(summary = "Start a scheduled task")
    @PostMapping("/{id}/start")
    fun start(@PathVariable id: Long): ResultVo<Void> = agentTaskService.startTask(id)

    @Operation(summary = "Pause a scheduled task")
    @PostMapping("/{id}/pause")
    fun pause(@PathVariable id: Long): ResultVo<Void> = agentTaskService.pauseTask(id)

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
        ResultVo.success(agentTaskLogService.page(id, taskName, status, startTimeFrom, startTimeTo, keyword, pageNum, pageSize))
    } catch (e: Exception) {
        log.error("Failed to query agent task logs", e)
        ResultVo.error("Failed to query agent task logs")
    }

    @Operation(summary = "Get available agents list")
    @GetMapping("/agents")
    fun agents(): ResultVo<List<Map<String, Any?>>> = try {
        val agents = agentService.getActiveAgents()
        ResultVo.success(agents.map { mapOf("id" to it.id, "name" to it.name) })
    } catch (e: Exception) {
        log.error("Failed to query agents list", e)
        ResultVo.error("Failed to query agents list")
    }
}
