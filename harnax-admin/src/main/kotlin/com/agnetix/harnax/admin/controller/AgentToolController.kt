package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.admin.service.AgentToolService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/tools")
@Tag(name = "Agent Tool Management", description = "Agent tool related APIs")
class AgentToolController(
    private val agentToolService: AgentToolService,
) {

    private val log = LoggerFactory.getLogger(AgentToolController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get tool list with pagination", description = "Paginated query for tool information")
    fun pageAgentTool(
        @Parameter(description = "Page number", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
        @Parameter(description = "Keyword") @RequestParam(name = "keyword", required = false) keyword: String?,
        @Parameter(description = "Status filter") @RequestParam(name = "status", required = false) status: Int?,
        @Parameter(description = "Type filter (BUILTIN/CUSTOM/HTTP)") @RequestParam(name = "type", required = false) type: String?,
    ): ResultVo<Page<AgentToolResponse>> = try {
        val page = agentToolService.page(keyword, status, type, pageNum ?: 1, pageSize ?: 10)
        ResultVo.success(page.mapRecords { agentToolService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get tool list", e)
        ResultVo.error(e.message ?: "Failed to get tool list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get tool details", description = "Get tool information by ID")
    fun getAgentTool(
        @Parameter(description = "Tool ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<AgentToolResponse?> = try {
        val tool = agentToolService.getAgentTool(id)
        ResultVo.success(tool?.let { agentToolService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get tool details", e)
        ResultVo.error(e.message ?: "Failed to get tool details")
    }

    @PutMapping("/update/{id}")
    @Operation(
        summary = "Update tool",
        description = "Update a CUSTOM/HTTP tool by ID. Builtin tools are rejected: they are owned by the code sync",
    )
    fun updateAgentTool(
        @Parameter(description = "Tool ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: AgentToolUpdateRequest,
    ): ResultVo<Void> = try {
        if (agentToolService.updateAgentTool(id, request)) ResultVo.success() else ResultVo.error("Failed to update tool")
    } catch (e: Exception) {
        log.error("Failed to update tool", e)
        ResultVo.error(e.message ?: "Failed to update tool")
    }

    @PutMapping("/toggle/{id}")
    @Operation(
        summary = "Toggle tool status",
        description = "Enable or disable a CUSTOM/HTTP tool. Builtin tools are always enabled by the code sync and are rejected here",
    )
    fun toggleAgentTool(
        @Parameter(description = "Tool ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Status (0: disabled 1: enabled)") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (agentToolService.toggleAgentToolStatus(id, status)) ResultVo.success() else ResultVo.error("Failed to toggle status")
    } catch (e: Exception) {
        log.error("Failed to toggle tool status", e)
        ResultVo.error(e.message ?: "Failed to toggle status")
    }

    @DeleteMapping("/{id}")
    @Operation(
        summary = "Delete tool",
        description = "Logically delete a CUSTOM/HTTP tool by ID. Builtin tools are rejected: the code sync removes them when the code deletes them",
    )
    fun deleteAgentTool(
        @Parameter(description = "Tool ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (agentToolService.deleteAgentTool(id)) ResultVo.success() else ResultVo.error("Failed to delete tool")
    } catch (e: Exception) {
        log.error("Failed to delete tool", e)
        ResultVo.error(e.message ?: "Failed to delete tool")
    }

    @GetMapping("/available")
    @Operation(summary = "Get available tools", description = "Get all enabled tools for agent configuration, optionally filtered by type")
    fun getAvailableTools(
        @Parameter(description = "Type filter (BUILTIN/CUSTOM/HTTP)") @RequestParam(name = "type", required = false) type: String?,
    ): ResultVo<List<AgentToolResponse>> = try {
        val tools = agentToolService.getAvailableToolsByType(type)
        ResultVo.success(tools.map { agentToolService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get available tools", e)
        ResultVo.error(e.message ?: "Failed to get available tools")
    }

    @GetMapping("/builtin")
    @Operation(
        summary = "Get builtin tools",
        description = "Get every builtin tool registered by the code sync (active rows, always enabled). Read-only: builtin tools cannot be written through this API",
    )
    fun getBuiltinTools(): ResultVo<List<AgentToolResponse>> = try {
        val tools = agentToolService.getBuiltinTools()
        ResultVo.success(tools.map { agentToolService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get builtin tools", e)
        ResultVo.error(e.message ?: "Failed to get builtin tools")
    }

    @GetMapping("/{id}/required-env-params")
    @Operation(summary = "Get required env param keys", description = "Get required environment parameter keys for a tool")
    fun getRequiredEnvParamKeys(
        @Parameter(description = "Tool ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<List<String>> = try {
        val keys = agentToolService.getRequiredEnvParamKeys(id)
        ResultVo.success(keys)
    } catch (e: Exception) {
        log.error("Failed to get required env param keys", e)
        ResultVo.error(e.message ?: "Failed to get required env param keys")
    }
}
