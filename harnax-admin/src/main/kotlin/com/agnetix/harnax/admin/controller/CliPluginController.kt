package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.service.CliPluginService
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.CliPlugin
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/cli-plugins")
@Tag(name = "CLI Plugin Management", description = "CLI plugin related APIs")
class CliPluginController(
    private val cliPluginService: CliPluginService,
) {

    private val log = LoggerFactory.getLogger(CliPluginController::class.java)

    @GetMapping
    @Operation(summary = "Get CLI plugin list", description = "List all CLI plugins, optionally filtered by type")
    fun listCliPlugins(
        @Parameter(description = "Type filter (SYSTEM/CUSTOM)") @RequestParam(name = "type", required = false) type: String?,
    ): ResultVo<List<CliPlugin>> = try {
        ResultVo.success(cliPluginService.list(type))
    } catch (e: Exception) {
        log.error("Failed to get CLI plugin list", e)
        ResultVo.error(e.message ?: "Failed to get CLI plugin list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get CLI plugin details", description = "Get CLI plugin information by ID")
    fun getCliPlugin(
        @Parameter(description = "Plugin ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<CliPlugin?> = try {
        ResultVo.success(cliPluginService.getById(id))
    } catch (e: Exception) {
        log.error("Failed to get CLI plugin details", e)
        ResultVo.error(e.message ?: "Failed to get CLI plugin details")
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle CLI plugin status", description = "Enable or disable a CLI plugin")
    fun toggleCliPlugin(
        @Parameter(description = "Plugin ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Status (0: disabled 1: enabled)") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (cliPluginService.toggleStatus(id, status)) ResultVo.success() else ResultVo.error("Failed to toggle CLI plugin status")
    } catch (e: Exception) {
        log.error("Failed to toggle CLI plugin status", e)
        ResultVo.error(e.message ?: "Failed to toggle CLI plugin status")
    }
}
