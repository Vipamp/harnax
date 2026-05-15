package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.config.RequiresEdition
import com.vipamp.vipclaw.admin.dto.ModelProviderCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelProviderResponse
import com.vipamp.vipclaw.admin.dto.ModelProviderUpdateRequest
import com.vipamp.vipclaw.admin.dto.ModelStatsInfo
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.mapRecords
import com.vipamp.vipclaw.admin.service.ModelProviderService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/**
 * Model provider controller
 * Available only for public edition (model marketplace feature)
 */
@RestController
@RequestMapping("/api/model-providers")
@Tag(name = "Model Provider Management", description = "Model provider CRUD APIs")
@RequiresEdition("public")
class ModelProviderController(
    private val modelProviderService: ModelProviderService,
) {

    /**
     * Paginated query for model providers
     */
    @GetMapping("/page")
    @Operation(summary = "Get model provider list with pagination", description = "Paginated query for model provider list")
    fun pageModelProvider(
        @Parameter(description = "Provider name") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "Provider type") @RequestParam(name = "type", required = false) type: String?,
        @Parameter(description = "Status") @RequestParam(name = "status", required = false) status: Int?,
        @Parameter(description = "Is public") @RequestParam(name = "isPublic", required = false) isPublic: Int?,
        @Parameter(description = "Page number") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
        @Parameter(description = "Page size") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
    ): ResultVo<Page<ModelProviderResponse>> = try {
        val page = modelProviderService.page(
            name,
            type,
            status,
            isPublic,
            pageNum ?: 1,
            pageSize ?: 10,
        )
        ResultVo.success(page.mapRecords { modelProviderService.convertToResponse(it) })
    } catch (e: Exception) {
        ResultVo.error(e.message ?: "Failed to get model provider list")
    }

    /**
     * Get model provider details
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get model provider details", description = "Get model provider details by ID")
    fun getModelProvider(
        @Parameter(description = "Model provider ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<ModelProviderResponse?> {
        val response = modelProviderService.getModelProvider(id)
        return ResultVo.success(response?.let { modelProviderService.convertToResponse(it) })
    }

    /**
     * Create model provider
     */
    @PostMapping
    @Operation(summary = "Create model provider", description = "Create a new model provider")
    fun createModelProvider(
        @Valid @RequestBody request: ModelProviderCreateRequest,
    ): ResultVo<Void> = if (modelProviderService.createModelProvider(request)) {
        ResultVo.success()
    } else {
        ResultVo.error("Failed to create model provider")
    }

    /**
     * Update model provider
     */
    @PutMapping("/update/{id}")
    @Operation(summary = "Update model provider", description = "Update model provider information")
    fun updateModelProvider(
        @Parameter(description = "Model provider ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: ModelProviderUpdateRequest,
    ): ResultVo<Void> = if (modelProviderService.updateModelProvider(id, request)) {
        ResultVo.success()
    } else {
        ResultVo.error("Failed to update model provider")
    }

    /**
     * Toggle model provider status
     */
    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle model provider status", description = "Enable/disable model provider")
    fun toggleModelProvider(
        @Parameter(description = "Model provider ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Enable status (0: disabled 1: enabled)") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = if (modelProviderService.toggleModelProvider(id, status)) {
        ResultVo.success()
    } else {
        ResultVo.error("Failed to toggle model provider status")
    }

    /**
     * Delete model provider
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete model provider", description = "Delete specified model provider")
    fun delete(
        @Parameter(description = "Model provider ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = if (modelProviderService.deleteModelProvider(id)) ResultVo.success() else ResultVo.error("Failed to delete model provider")

    /**
     * Connectivity test
     */
    @PostMapping("/{id}/test")
    @Operation(summary = "Connectivity test", description = "Test if model provider connection is normal")
    fun connectivityTest(
        @Parameter(description = "Model provider ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Boolean> {
        val result = modelProviderService.connectivityTest(id)
        return ResultVo.success(result)
    }

    /**
     * Get model statistics
     */
    @GetMapping("/{id}/stats")
    @Operation(summary = "Get model statistics", description = "Get model statistics for specified provider")
    fun getModelStats(
        @Parameter(description = "Provider ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<ModelStatsInfo> {
        val stats = modelProviderService.getModelStats(id)
        return ResultVo.success(stats)
    }
}
