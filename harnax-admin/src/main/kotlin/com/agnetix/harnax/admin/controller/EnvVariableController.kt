package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.admin.service.EnvVariableService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/env-variables")
@Tag(name = "Environment Variable Management", description = "Environment variable CRUD APIs")
class EnvVariableController(
    private val envVariableService: EnvVariableService,
) {

    private val log = LoggerFactory.getLogger(EnvVariableController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get env variable list with pagination", description = "Paginated query, only returns variables created by current user")
    fun page(
        @Parameter(description = "Page number", example = "1") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
        @Parameter(description = "Keyword") @RequestParam(name = "keyword", required = false) keyword: String?,
    ): ResultVo<Page<EnvVariableResponse>> = try {
        val page = envVariableService.page(keyword, pageNum ?: 1, pageSize ?: 10)
        ResultVo.success(page.mapRecords { envVariableService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get env variable list", e)
        ResultVo.error("Failed to get env variable list")
    }

    @GetMapping("/list")
    @Operation(summary = "List env variables for agent config", description = "Returns all enabled env variables with real values for agent config dropdown")
    fun listForAgentConfig(): ResultVo<List<Map<String, Any?>>> = try {
        ResultVo.success(envVariableService.listForAgentConfig())
    } catch (e: Exception) {
        log.error("Failed to list env variables for agent config", e)
        ResultVo.error("Failed to list env variables for agent config")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get env variable details", description = "Get env variable by ID")
    fun getById(
        @Parameter(description = "Env Variable ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<EnvVariableResponse?> = try {
        val env = envVariableService.getEnvVariable(id)
        ResultVo.success(env?.let { envVariableService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get env variable details", e)
        ResultVo.error("Failed to get env variable details")
    }

    @PostMapping
    @Operation(summary = "Create env variable", description = "Create a new environment variable")
    fun create(
        @Valid @RequestBody request: EnvVariableCreateRequest,
    ): ResultVo<Void> = try {
        if (envVariableService.createEnvVariable(request)) ResultVo.success() else ResultVo.error("Failed to create env variable")
    } catch (e: Exception) {
        log.error("Failed to create env variable", e)
        ResultVo.error("Failed to create env variable")
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "Update env variable", description = "Update environment variable by ID")
    fun update(
        @Parameter(description = "Env Variable ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: EnvVariableUpdateRequest,
    ): ResultVo<Void> = try {
        if (envVariableService.updateEnvVariable(id, request)) ResultVo.success() else ResultVo.error("Failed to update env variable")
    } catch (e: Exception) {
        log.error("Failed to update env variable", e)
        ResultVo.error("Failed to update env variable")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete env variable", description = "Logical delete env variable by ID")
    fun delete(
        @Parameter(description = "Env Variable ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (envVariableService.deleteEnvVariable(id)) ResultVo.success() else ResultVo.error("Failed to delete env variable")
    } catch (e: Exception) {
        log.error("Failed to delete env variable", e)
        ResultVo.error("Failed to delete env variable")
    }

    @PutMapping("/{id}/toggle")
    @Operation(summary = "Toggle env variable enabled status", description = "Enable or disable an environment variable")
    fun toggleEnabled(
        @Parameter(description = "Env Variable ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Enabled status (0: Disabled, 1: Enabled)") @RequestParam(name = "enabled") enabled: Int,
    ): ResultVo<Void> = try {
        if (envVariableService.toggleEnabled(id, enabled)) ResultVo.success() else ResultVo.error("Failed to toggle env variable")
    } catch (e: Exception) {
        log.error("Failed to toggle env variable", e)
        ResultVo.error("Failed to toggle env variable")
    }
}
