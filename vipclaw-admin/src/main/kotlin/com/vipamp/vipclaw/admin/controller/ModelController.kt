package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.config.RequiresEdition
import com.vipamp.vipclaw.admin.dto.ModelCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelResponse
import com.vipamp.vipclaw.admin.dto.ModelUpdateRequest
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.mapRecords
import com.vipamp.vipclaw.admin.service.ModelService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/**
 * Model controller
 * Available only for public edition (model marketplace feature)
 *
 * @author vipamp
 * @since 2026-03-13
 */
@RestController
@RequestMapping("/api/models")
@Tag(name = "Model Management", description = "Model CRUD APIs")
@RequiresEdition("public")
class ModelController(
    private val modelService: ModelService,
) {

    /**
     * Paginated query for models
     */
    @GetMapping("/page")
    @Operation(summary = "Get model list with pagination", description = "Paginated query for model list")
    fun pageModel(
        @Parameter(description = "Name") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "Provider ID") @RequestParam(name = "providerId", required = false) providerId: Long?,
        @Parameter(description = "Model type") @RequestParam(name = "modelType", required = false) modelType: String?,
        @Parameter(description = "Status") @RequestParam(name = "status", required = false) status: Int?,
        @Parameter(description = "Tag filter (supports multiple, e.g.: internet,reasoning,tool,mcp,vision)") @RequestParam(
            name = "tags",
            required = false,
        ) tags: String?,
        @Parameter(description = "Minimum price") @RequestParam(name = "minPrice", required = false) minPrice: Double?,
        @Parameter(description = "Maximum price") @RequestParam(name = "maxPrice", required = false) maxPrice: Double?,
        @Parameter(description = "Page number") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
        @Parameter(description = "Page size") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?,
    ): ResultVo<Page<ModelResponse>> = try {
        val page = modelService.page(
            name, providerId, modelType, status, tags, minPrice, maxPrice,
            pageNum ?: 1,
            pageSize ?: 10,
        )
        ResultVo.success(page.mapRecords { modelService.convertToResponse(it) })
    } catch (e: Exception) {
        ResultVo.error(e.message ?: "Failed to get model list")
    }

    /**
     * Get model details
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get model details", description = "Get model details by ID")
    fun getModel(
        @Parameter(description = "Model ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<ModelResponse?> {
        val model = modelService.getModel(id)
        return ResultVo.success(model?.let { modelService.convertToResponse(it) })
    }

    /**
     * Create model
     */
    @PostMapping
    @Operation(summary = "Create model", description = "Create a new model")
    fun createModel(
        @Valid @RequestBody request: ModelCreateRequest,
    ): ResultVo<Void> = if (modelService.createModel(request)) {
        ResultVo.success()
    } else {
        ResultVo.error("Failed to create model")
    }

    /**
     * Update model
     */
    @PutMapping("/update/{id}")
    @Operation(summary = "Update model", description = "Update model information")
    fun updateModel(
        @Parameter(description = "Model ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: ModelUpdateRequest,
    ): ResultVo<Void> = if (modelService.updateModel(id, request)) {
        ResultVo.success()
    } else {
        ResultVo.error("Failed to update model")
    }

    /**
     * Toggle model status
     */
    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle model status", description = "Enable/disable model")
    fun toggleModel(
        @Parameter(description = "Model ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Enable status (0: disabled 1: enabled)") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> {
        if (modelService.toggleModel(id, status)) return ResultVo.success()
        return ResultVo.error("Failed to toggle model status")
    }

    /**
     * Delete model
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete model", description = "Delete specified model")
    fun deleteModel(
        @Parameter(description = "Model ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> {
        modelService.deleteModel(id)
        return ResultVo.success()
    }
}
