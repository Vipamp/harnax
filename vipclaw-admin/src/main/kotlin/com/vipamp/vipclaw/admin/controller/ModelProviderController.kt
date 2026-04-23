package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.admin.dto.ModelProviderCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelProviderResponse
import com.vipamp.vipclaw.admin.dto.ModelProviderUpdateRequest
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.entity.ModelProvider
import com.vipamp.vipclaw.admin.service.ModelProviderService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/**
 * 模型服务商控制器
 *
 * @author vipamp
 * @since 2026-03-13
 */
@RestController
@RequestMapping("/admin/model-providers")
@Tag(name = "模型服务商管理", description = "模型服务商的增删改查接口")
class ModelProviderController(
    private val modelProviderService: ModelProviderService
) {

    /**
     * 分页查询模型服务商
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询模型服务商", description = "分页查询模型服务商列表")
    fun page(
        @Parameter(description = "当前页码") @RequestParam(name = "pageNum", defaultValue = "1") pageNum: Long?,
        @Parameter(description = "每页条数") @RequestParam(name = "pageSize", defaultValue = "10") pageSize: Long?,
        @Parameter(description = "服务商名称") @RequestParam(name = "name", required = false) name: String?,
        @Parameter(description = "状态") @RequestParam(name = "status", required = false) status: Int?,
        @Parameter(description = "是否公开") @RequestParam(name = "isPublic", required = false) isPublic: Int?
    ): ResultVo<Page<ModelProviderResponse>> {
        val page = Page<ModelProvider>((pageNum ?: 1), pageSize ?: 10)
        val result = modelProviderService.page(page, name, status, isPublic)
        return ResultVo.success(result)
    }

    /**
     * 获取模型服务商详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取模型服务商详情", description = "根据 ID 获取模型服务商详情")
    fun getDetail(
        @Parameter(description = "模型服务商 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<ModelProviderResponse> {
        val response = modelProviderService.getDetail(id)
        return ResultVo.success(response)
    }

    /**
     * 创建模型服务商
     */
    @PostMapping
    @Operation(summary = "创建模型服务商", description = "创建新的模型服务商")
    fun create(
        @Valid @RequestBody request: ModelProviderCreateRequest
    ): ResultVo<ModelProviderResponse> {
        val response = modelProviderService.create(request)
        return ResultVo.success(response)
    }

    /**
     * 更新模型服务商
     */
    @PutMapping("/update/{id}")
    @Operation(summary = "更新模型服务商", description = "更新模型服务商信息")
    fun update(
        @Parameter(description = "模型服务商 ID") @PathVariable(name = "id") id: Long,
        @Valid @RequestBody request: ModelProviderUpdateRequest
    ): ResultVo<ModelProviderResponse> {
        val response = modelProviderService.update(id, request)
        return ResultVo.success(response)
    }

    /**
     * 切换模型服务商状态
     */
    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换模型服务商状态", description = "启用/禁用模型服务商")
    fun toggle(
        @Parameter(description = "模型服务商 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<ModelProviderResponse> {
        val response = modelProviderService.toggle(id)
        return ResultVo.success(response)
    }

    /**
     * 删除模型服务商
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型服务商", description = "删除指定的模型服务商")
    fun delete(
        @Parameter(description = "模型服务商 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<Void> {
        modelProviderService.removeProviderById(id)
        return ResultVo.success()
    }

    /**
     * 连接测试
     */
    @PostMapping("/{id}/test")
    @Operation(summary = "连接测试", description = "测试模型服务商连接是否正常")
    fun connectivityTest(
        @Parameter(description = "模型服务商 ID") @PathVariable(name = "id") id: Long
    ): ResultVo<Boolean> {
        val result = modelProviderService.connectivityTest(id)
        return ResultVo.success(result)
    }
}
