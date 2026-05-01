package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.config.RequiresEdition
import com.vipamp.vipclaw.admin.dto.ModelCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelResponse
import com.vipamp.vipclaw.admin.dto.ModelUpdateRequest
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.service.ModelService
import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.common.page.mapRecords
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/**
 * 模型控制器
 * 仅公有云版可用(模型市场功能)
 *
 * @author vipamp
 * @since 2026-03-13
 */
@RestController
@RequestMapping("/api/models")
@Tag(name = "模型管理", description = "模型的增删改查接口")
@RequiresEdition("public")
class ModelController(
    private val modelService: ModelService
) {

    /**
     * 分页查询模型
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询模型", description = "分页查询模型列表")
    fun pageModel(
        @Parameter(description = "名称") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "供应商ID") @RequestParam(name = "providerId", required = false) providerId: Long?,
        @Parameter(description = "模型类型") @RequestParam(name = "modelType", required = false) modelType: String?,
        @Parameter(description = "状态") @RequestParam(name = "status", required = false) status: Int?,
        @Parameter(description = "标签筛选（支持多个，如：internet,reasoning,tool,mcp,vision）") @RequestParam(
            name = "tags",
            required = false
        ) tags: String?,
        @Parameter(description = "最低价格") @RequestParam(name = "minPrice", required = false) minPrice: Double?,
        @Parameter(description = "最高价格") @RequestParam(name = "maxPrice", required = false) maxPrice: Double?,
        @Parameter(description = "当前页码") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Int?,
        @Parameter(description = "每页条数") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Int?
    ): ResultVo<Page<ModelResponse>> {
        return try {
            val page = modelService.page(
                name, providerId, modelType, status, tags, minPrice, maxPrice,
                pageNum ?: 1,
                pageSize ?: 10
            )
            ResultVo.success(page.mapRecords { modelService.convertToResponse(it) })
        } catch (e: Exception) {
            ResultVo.error(e.message ?: "获取模型列表失败")
        }
    }

    /**
     * 获取模型详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取模型详情", description = "根据 ID 获取模型详情")
    fun getModel(
        @Parameter(description = "模型ID") @PathVariable(name = "id") id: Long
    ): ResultVo<ModelResponse?> {
        val model = modelService.getModel(id)
        return ResultVo.success(model?.let { modelService.convertToResponse(it) })
    }

    /**
     * 创建模型
     */
    @PostMapping
    @Operation(summary = "创建模型", description = "创建新的模型")
    fun createModel(
        @Valid @RequestBody request: ModelCreateRequest
    ): ResultVo<Void> =
        if (modelService.createModel(request)) ResultVo.success()
        else ResultVo.error("创建模型失败")

    /**
     * 更新模型
     */
    @PutMapping("/update/{id}")
    @Operation(summary = "更新模型", description = "更新模型信息")
    fun updateModel(
        @Parameter(description = "模型ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: ModelUpdateRequest
    ): ResultVo<Void> =
        if (modelService.updateModel(id, request)) ResultVo.success()
        else ResultVo.error("更新模型失败")

    /**
     * 切换模型状态
     */
    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换模型状态", description = "启用/禁用模型")
    fun toggleModel(
        @Parameter(description = "模型ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "启用状态（0:禁用 1:启用）") @RequestParam(name = "status") status: Int
    ): ResultVo<Void> {
        if (modelService.toggleModel(id, status)) return ResultVo.success()
        return ResultVo.error("切换模型状态失败")
    }

    /**
     * 删除模型
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型", description = "删除指定的模型")
    fun deleteModel(
        @Parameter(description = "模型ID") @PathVariable(name = "id") id: Long
    ): ResultVo<Void> {
        modelService.deleteModel(id)
        return ResultVo.success()
    }
}
