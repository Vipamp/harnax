package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.CliResponse
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.CliService
import com.agnetix.harnax.admin.service.impl.AgentSessionRefreshService
import com.agnetix.harnax.admin.service.impl.RelatedAgentInfo
import com.agnetix.harnax.admin.service.impl.RelatedSessionInfo
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * CLI read side: the registered plugin packages and the switch that takes one out of circulation.
 *
 * There is no create/update/delete route. A CLI is published by dropping its `.harnaxcli.zip` into
 * admin's package directory, which `CliPackageAutoRegistrar` reads at startup (design D2); the
 * `related-agents` and `related-sessions` reads exist so the effect of the switch is visible before
 * it is thrown.
 */
@RestController
@RequestMapping("/api/admin/clis")
@Tag(name = "CLI Management", description = "CLI tool related APIs")
class CliController(
    private val cliService: CliService,
    private val agentSessionRefreshService: AgentSessionRefreshService,
    private val messageUtil: MessageUtil,
) {

    private val log = LoggerFactory.getLogger(CliController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get CLI list with pagination", description = "Paginated query for CLI tool information")
    fun pageCli(
        @Parameter(description = "Page number", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "CLI name") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "Status filter") @RequestParam(name = "status", required = false) status: Int?,
    ): ResultVo<Page<CliResponse>> = try {
        val page = cliService.page(name, status, pageNum ?: 1, pageSize ?: 10)
        val responsePage = page.mapRecords { cliService.convertToResponse(it) }
        ResultVo.success(responsePage)
    } catch (e: Exception) {
        log.error("Failed to get CLI list", e)
        ResultVo.error(e.message ?: "Failed to get CLI list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get CLI details", description = "Get CLI information by CLI ID")
    fun getCli(
        @Parameter(description = "CLI ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<CliResponse> = try {
        val cli = cliService.getCli(id)
            ?: return ResultVo.error(404, messageUtil.getMessage("error.cli.notfound"))
        ResultVo.success(cliService.convertToResponse(cli))
    } catch (e: Exception) {
        log.error("Failed to get CLI details", e)
        ResultVo.error(e.message ?: "Failed to get CLI details")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle CLI status", description = "Toggle CLI status by CLI ID")
    fun toggleCli(
        @Parameter(description = "CLI ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "CLI status") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (cliService.toggleCliStatus(id, status)) ResultVo.success() else ResultVo.error("Failed to toggle CLI status")
    } catch (e: Exception) {
        log.error("Failed to toggle CLI status", e)
        ResultVo.error(e.message ?: "Failed to toggle CLI status")
    }

    @GetMapping("/{id}/related-agents")
    @Operation(summary = "List agents bound to a CLI", description = "Used to warn before disabling/deleting a CLI still in use")
    fun relatedAgents(
        @Parameter(description = "CLI ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<List<RelatedAgentInfo>> = try {
        ResultVo.success(agentSessionRefreshService.listAgentsByCli(id))
    } catch (e: Exception) {
        log.error("Failed to list agents for CLI {}", id, e)
        ResultVo.error(e.message ?: "Failed to list related agents")
    }

    @GetMapping("/{id}/related-sessions")
    @Operation(
        summary = "List sessions affected by a CLI",
        description = "Sessions of all agents bound to this CLI; push REFRESH via /api/admin/agents/refresh-sessions so they pick up the new CLI config",
    )
    fun relatedSessions(
        @Parameter(description = "CLI ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<List<RelatedSessionInfo>> = try {
        ResultVo.success(agentSessionRefreshService.listSessionsByCli(id))
    } catch (e: Exception) {
        log.error("Failed to list sessions for CLI {}", id, e)
        ResultVo.error(e.message ?: "Failed to list related sessions")
    }
}
