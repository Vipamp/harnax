package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.config.RequiresEdition
import com.agnetix.harnax.admin.dto.AgentCreateRequest
import com.agnetix.harnax.admin.dto.AgentResponse
import com.agnetix.harnax.admin.dto.AgentUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

/**
 * Agent management controller
 * Available for enterprise and public editions (Agent sharing feature)
 */
@RestController
@RequestMapping("/api/agents")
@Tag(name = "Agent Management", description = "Agent related APIs")
@RequiresEdition("enterprise", "public")
class AgentController(
    private val agentService: AgentService,
) {

    private val log = LoggerFactory.getLogger(AgentController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get agent list with pagination", description = "Paginated query for agent information")
    fun pageAgent(
        @Parameter(description = "Page number", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "Agent name") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "Status filter") @RequestParam(name = "status", required = false) status: Int?,
    ): ResultVo<Page<AgentResponse>> = try {
        val page = agentService.page(name, status, pageNum ?: 1, pageSize ?: 10)
        ResultVo.success(page.mapRecords { agentService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get agent list", e)
        ResultVo.error(e.message ?: "Failed to get agent list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get agent details", description = "Get agent information by agent ID")
    fun getAgent(
        @Parameter(description = "Agent ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<AgentResponse?> = try {
        ResultVo.success(agentService.getAgent(id)?.let { agentService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get agent details", e)
        ResultVo.error(e.message ?: "Failed to get agent details")
    }

    @PostMapping
    @Operation(summary = "Create agent", description = "Add new agent information")
    fun createAgent(
        @Validated @RequestBody request: AgentCreateRequest,
    ): ResultVo<Void> = try {
        if (agentService.createAgent(request)) ResultVo.success() else ResultVo.error("Failed to create agent")
    } catch (e: Exception) {
        log.error("Failed to create agent", e)
        ResultVo.error(e.message ?: "Failed to create agent")
    }

    @PutMapping("/update/{agentId}")
    @Operation(summary = "Update agent", description = "Update agent information by agent ID")
    fun updateAgent(
        @Parameter(description = "Agent ID") @PathVariable(name = "agentId") agentId: Long,
        @Validated @RequestBody request: AgentUpdateRequest,
    ): ResultVo<Void> = try {
        if (agentService.updateAgent(agentId, request)) ResultVo.success() else ResultVo.error("Failed to update agent")
    } catch (e: Exception) {
        log.error("Failed to update agent", e)
        ResultVo.error(e.message ?: "Failed to update agent")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle agent status", description = "Toggle agent status by agent ID")
    fun toggleAgent(
        @Parameter(description = "Agent ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Agent status") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (agentService.toggleAgentStatus(id, status)) ResultVo.success() else ResultVo.error("Failed to toggle agent status")
    } catch (e: Exception) {
        log.error("Failed to update agent", e)
        ResultVo.error(e.message ?: "Failed to update agent")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete agent", description = "Delete agent by agent ID")
    fun deleteAgent(
        @Parameter(description = "Agent ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (agentService.deleteAgent(id)) ResultVo.success() else ResultVo.error("Failed to delete agent")
    } catch (e: Exception) {
        log.error("Failed to delete agent", e)
        ResultVo.error(e.message ?: "Failed to delete agent")
    }
}
