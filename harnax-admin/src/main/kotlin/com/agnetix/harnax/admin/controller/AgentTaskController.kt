package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.AgentTaskCreateRequest
import com.agnetix.harnax.admin.dto.AgentTaskUpdateRequest
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.service.AgentTaskLogService
import com.agnetix.harnax.admin.service.AgentTaskService
import com.agnetix.harnax.common.dto.ResultVo
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
    ): ResultVo<*> {
        return try {
            ResultVo.success(agentTaskService.page(name, agentId, taskStatus, pageNum, pageSize))
        } catch (e: Exception) {
            log.error("Failed to query agent task list", e)
            ResultVo.error("Failed to query agent task list: ${e.message}")
        }
    }

    @Operation(summary = "Get agent task by ID")
    @GetMapping("/{id}")
    fun getById(@PathVariable id: Long): ResultVo<*> {
        return try {
            val task = agentTaskService.getAgentTask(id)
                ?: return ResultVo.error("Agent task not found")
            ResultVo.success(agentTaskService.convertToResponse(task))
        } catch (e: Exception) {
            log.error("Failed to get agent task", e)
            ResultVo.error("Failed to get agent task: ${e.message}")
        }
    }

    @Operation(summary = "Create agent task")
    @PostMapping
    fun create(@Valid @RequestBody request: AgentTaskCreateRequest): ResultVo<*> {
        return try {
            val success = agentTaskService.createAgentTask(request)
            if (success) ResultVo.success()
            else ResultVo.error("Create failed")
        } catch (e: Exception) {
            log.error("Failed to create agent task", e)
            ResultVo.error("Failed to create agent task: ${e.message}")
        }
    }

    @Operation(summary = "Update agent task")
    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: AgentTaskUpdateRequest): ResultVo<*> {
        return try {
            val success = agentTaskService.updateAgentTask(id, request)
            if (success) ResultVo.success()
            else ResultVo.error("Update failed")
        } catch (e: Exception) {
            log.error("Failed to update agent task", e)
            ResultVo.error("Failed to update agent task: ${e.message}")
        }
    }

    @Operation(summary = "Delete agent task")
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResultVo<*> {
        return try {
            val success = agentTaskService.deleteAgentTask(id)
            if (success) ResultVo.success()
            else ResultVo.error("Delete failed")
        } catch (e: Exception) {
            log.error("Failed to delete agent task", e)
            ResultVo.error("Failed to delete agent task: ${e.message}")
        }
    }

    @Operation(summary = "Start agent task")
    @PostMapping("/{id}/start")
    fun start(@PathVariable id: Long): ResultVo<*> {
        return try {
            val success = agentTaskService.startTask(id)
            if (success) ResultVo.success()
            else ResultVo.error("Start failed")
        } catch (e: Exception) {
            log.error("Failed to start agent task", e)
            ResultVo.error("Failed to start agent task: ${e.message}")
        }
    }

    @Operation(summary = "Pause agent task")
    @PostMapping("/{id}/pause")
    fun pause(@PathVariable id: Long): ResultVo<*> {
        return try {
            val success = agentTaskService.pauseTask(id)
            if (success) ResultVo.success()
            else ResultVo.error("Pause failed")
        } catch (e: Exception) {
            log.error("Failed to pause agent task", e)
            ResultVo.error("Failed to pause agent task: ${e.message}")
        }
    }

    @Operation(summary = "Run agent task once")
    @PostMapping("/{id}/run")
    fun runOnce(@PathVariable id: Long): ResultVo<*> {
        return try {
            val success = agentTaskService.runTaskOnce(id)
            if (success) ResultVo.success()
            else ResultVo.error("Run once failed")
        } catch (e: Exception) {
            log.error("Failed to run agent task once", e)
            ResultVo.error("Failed to run agent task once: ${e.message}")
        }
    }

    @Operation(summary = "Get agent task execution logs")
    @GetMapping("/{id}/logs")
    fun logs(
        @PathVariable id: Long,
        @RequestParam(required = false) taskName: String?,
        @RequestParam(required = false) status: Int?,
        @RequestParam(defaultValue = "1") pageNum: Int,
        @RequestParam(defaultValue = "10") pageSize: Int,
    ): ResultVo<*> {
        return try {
            ResultVo.success(agentTaskLogService.page(id, taskName, status, pageNum, pageSize))
        } catch (e: Exception) {
            log.error("Failed to query agent task logs", e)
            ResultVo.error("Failed to query agent task logs: ${e.message}")
        }
    }

    @Operation(summary = "Get available agents list")
    @GetMapping("/agents")
    fun agents(): ResultVo<*> {
        return try {
            val agents = agentService.getActiveAgents()
            ResultVo.success(agents.map { mapOf("id" to it.id, "name" to it.name) })
        } catch (e: Exception) {
            log.error("Failed to query agents list", e)
            ResultVo.error("Failed to query agents list: ${e.message}")
        }
    }
}
