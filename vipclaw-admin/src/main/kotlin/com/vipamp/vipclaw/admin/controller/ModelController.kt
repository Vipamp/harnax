package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.admin.dto.ModelCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelResponse
import com.vipamp.vipclaw.admin.dto.ModelUpdateRequest
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.entity.Model
import com.vipamp.vipclaw.admin.service.ModelService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/**
 * 模型控制器
 *
 * @author vipamp
 * @since 2026-03-13
 */
@RestController
@RequestMapping("/admin/models")
@Tag(name = "模型管理", description = "模型的增删改查接口")
class ModelController(
    private val modelService: ModelService
) {

    /**
     * 分页查询模型
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询模型", description = "分页查询模型列表")
    fun page(
        @Parameter(description = "当前页码") @RequestParam(name = "current", defaultValue = "1") current: Long?,
        @Parameter(description = "每页条数") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Long?,
        @Parameter(description = "名称") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "供应商ID") @RequestParam(name = "providerId", required = false) providerId: Long?,
        @Parameter(description = "模型类型") @RequestParam(name = "modelType", required = false) modelType: String?,
        @Parameter(description = "状态") @RequestParam(name = "status", required = false) status: Int?,
        @Parameter(description = "标签筛选（支持多个，如：internet,reasoning,tool,mcp,vision）") @RequestParam(name = "tags", required = false) tags: String?,
        @Parameter(description = "最低价格") @RequestParam(name = "minPrice", required = false) minPrice: Double?,
        @Parameter(description = "最高价格") @RequestParam(name = "maxPrice", required = false) maxPrice: Double?
    ): ResultVo<Page<ModelResponse>> {
        val page = Page<Model>(current ?: 1, pageSize ?: 10)
        val result = modelService.page(page, name, providerId, modelType, status, tags, minPrice, maxPrice)
        return ResultVo.success(result)
    }

    /**
     * 获取模型详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取模型详情", description = "根据 ID 获取模型详情")
    fun getDetail(
        @Parameter(description = "模型ID") @PathVariable(name = "id") id: Long
    ): ResultVo<ModelResponse> {
        val response = modelService.getDetail(id)
        return ResultVo.success(response)
    }

    /**
     * 创建模型
     */
    @PostMapping
    @Operation(summary = "创建模型", description = "创建新的模型")
    fun create(
        @Valid @RequestBody request: ModelCreateRequest
    ): ResultVo<ModelResponse> {
        val response = modelService.create(request)
        return ResultVo.success(response)
    }

    /**
     * 更新模型
     */
    @PutMapping("/update/{id}")
    @Operation(summary = "更新模型", description = "更新模型信息")
    fun update(
        @Parameter(description = "模型ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: ModelUpdateRequest
    ): ResultVo<ModelResponse> {
        val response = modelService.update(id, request)
        return ResultVo.success(response)
    }

    /**
     * 切换模型状态
     */
    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换模型状态", description = "启用/禁用模型")
    fun toggle(
        @Parameter(description = "模型ID") @PathVariable(name = "id") id: Long
    ): ResultVo<ModelResponse> {
        val response = modelService.toggle(id)
        return ResultVo.success(response)
    }

    /**
     * 删除模型
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型", description = "删除指定的模型")
    fun delete(
        @Parameter(description = "模型ID") @PathVariable(name = "id") id: Long
    ): ResultVo<Void> {
        modelService.deleteById(id)
        return ResultVo.success()
    }
}
