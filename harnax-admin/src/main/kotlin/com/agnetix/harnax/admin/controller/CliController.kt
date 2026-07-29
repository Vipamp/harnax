package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.CliCreateRequest
import com.agnetix.harnax.admin.dto.CliResponse
import com.agnetix.harnax.admin.dto.CliUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.mapRecords
import com.agnetix.harnax.admin.service.CliService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * CLI management controller.
 * Manages command-line tools that are installed into agent sandbox images.
 */
@RestController
@RequestMapping("/api/admin/clis")
@Tag(name = "CLI Management", description = "CLI tool related APIs")
class CliController(
    private val cliService: CliService,
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
    ): ResultVo<CliResponse?> = try {
        val cli = cliService.getCli(id)
        ResultVo.success(cli?.let { cliService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get CLI details", e)
        ResultVo.error(e.message ?: "Failed to get CLI details")
    }

    @PostMapping
    @Operation(summary = "Create CLI", description = "Add new CLI tool information")
    fun createCli(
        @Valid @RequestBody request: CliCreateRequest,
    ): ResultVo<Void> = try {
        if (cliService.createCli(request)) ResultVo.success() else ResultVo.error("Failed to create CLI")
    } catch (e: Exception) {
        log.error("Failed to create CLI", e)
        ResultVo.error(e.message ?: "Failed to create CLI")
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "Update CLI", description = "Update CLI information by CLI ID")
    fun updateCli(
        @Parameter(description = "CLI ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: CliUpdateRequest,
    ): ResultVo<Void> = try {
        if (cliService.updateCli(id, request)) ResultVo.success() else ResultVo.error("Failed to update CLI")
    } catch (e: Exception) {
        log.error("Failed to update CLI", e)
        ResultVo.error(e.message ?: "Failed to update CLI")
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

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete CLI", description = "Delete CLI by CLI ID")
    fun deleteCli(
        @Parameter(description = "CLI ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (cliService.deleteCli(id)) ResultVo.success() else ResultVo.error("Failed to delete CLI")
    } catch (e: Exception) {
        log.error("Failed to delete CLI", e)
        ResultVo.error(e.message ?: "Failed to delete CLI")
    }
}
