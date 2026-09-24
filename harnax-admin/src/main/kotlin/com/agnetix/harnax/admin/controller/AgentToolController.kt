package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.AgentToolService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * Read-only: tools are registered by the code, so this API exposes no write path.
 */
@RestController
@RequestMapping("/api/admin/tools")
@Tag(name = "Agent Tool Management", description = "Agent tool related APIs")
class AgentToolController(
    private val agentToolService: AgentToolService,
    private val messageUtil: MessageUtil,
) {

    private val log = LoggerFactory.getLogger(AgentToolController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get tool list with pagination", description = "Paginated query for tool information")
    fun pageAgentTool(
        @Parameter(description = "Page number", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
        @Parameter(description = "Keyword") @RequestParam(name = "keyword", required = false) keyword: String?,
        @Parameter(description = "Status filter") @RequestParam(name = "status", required = false) status: Int?,
    ): ResultVo<Page<AgentToolResponse>> = try {
        val page = agentToolService.page(keyword, status, pageNum ?: 1, pageSize ?: 10)
        ResultVo.success(page.mapRecords { agentToolService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get tool list", e)
        ResultVo.error(e.message ?: "Failed to get tool list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get tool details", description = "Get tool information by ID")
    fun getAgentTool(
        @Parameter(description = "Tool ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<AgentToolResponse> = try {
        val tool = agentToolService.getAgentTool(id)
            ?: return ResultVo.error(404, messageUtil.getMessage("error.tool.notfound"))
        ResultVo.success(agentToolService.convertToResponse(tool))
    } catch (e: Exception) {
        log.error("Failed to get tool details", e)
        ResultVo.error(e.message ?: "Failed to get tool details")
    }

    @GetMapping("/available")
    @Operation(
        summary = "Get available tools",
        description = "Get every enabled tool an agent may bind to. Mandatory tools are excluded: they are injected at runtime",
    )
    fun getAvailableTools(): ResultVo<List<AgentToolResponse>> = try {
        val tools = agentToolService.getAvailableTools()
        ResultVo.success(tools.map { agentToolService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get available tools", e)
        ResultVo.error(e.message ?: "Failed to get available tools")
    }

    @GetMapping("/builtin")
    @Operation(
        summary = "Get builtin tools",
        description = "Get every tool registered by the code sync (active rows, always enabled). Read-only: tools cannot be written through this API",
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
