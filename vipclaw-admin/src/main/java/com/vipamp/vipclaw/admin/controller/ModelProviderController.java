package com.vipamp.vipclaw.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vipamp.vipclaw.admin.dto.ModelProviderCreateRequest;
import com.vipamp.vipclaw.admin.dto.ModelProviderResponse;
import com.vipamp.vipclaw.admin.dto.ModelProviderUpdateRequest;
import com.vipamp.vipclaw.admin.entity.ModelProvider;
import com.vipamp.vipclaw.admin.service.ModelProviderService;
import com.vipamp.vipclaw.admin.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 模型服务商控制器
 *
 * @author vipamp
 * @since 2026-03-13
 */
@RestController
@RequestMapping("/model-provider")
@Tag(name = "模型服务商管理", description = "模型服务商的增删改查接口")
public class ModelProviderController {

    @Autowired
    private ModelProviderService modelProviderService;

    /**
     * 分页查询模型服务商
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询模型服务商", description = "分页查询模型服务商列表")
    public Result<Page<ModelProviderResponse>> page(
            @Parameter(description = "当前页码") @RequestParam(name = "current", defaultValue = "1") Integer current,
            @Parameter(description = "每页条数") @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @Parameter(description = "服务商名称") @RequestParam(name = "name", required = false) String name,
            @Parameter(description = "状态") @RequestParam(name = "status", required = false) Integer status) {
        
        Page<ModelProvider> page = new Page<>(current, pageSize);
        Page<ModelProviderResponse> result = modelProviderService.page(page, name, status);
        return Result.success(result);
    }

    /**
     * 获取模型服务商详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取模型服务商详情", description = "根据ID获取模型服务商详情")
    public Result<ModelProviderResponse> getDetail(
            @Parameter(description = "模型服务商ID") @PathVariable Long id) {
        ModelProviderResponse response = modelProviderService.getDetail(id);
        return Result.success(response);
    }

    /**
     * 创建模型服务商
     */
    @PostMapping
    @Operation(summary = "创建模型服务商", description = "创建新的模型服务商")
    public Result<ModelProviderResponse> create(
            @Valid @RequestBody ModelProviderCreateRequest request) {
        ModelProviderResponse response = modelProviderService.create(request);
        return Result.success(response);
    }

    /**
     * 更新模型服务商
     */
    @PutMapping("/update/{id}")
    @Operation(summary = "更新模型服务商", description = "更新模型服务商信息")
    public Result<ModelProviderResponse> update(
            @Parameter(description = "模型服务商ID") @PathVariable Long id,
            @Valid @RequestBody ModelProviderUpdateRequest request) {
        request.setId(id);
        ModelProviderResponse response = modelProviderService.update(id, request);
        return Result.success(response);
    }

    /**
     * 切换模型服务商状态
     */
    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换模型服务商状态", description = "启用/禁用模型服务商")
    public Result<ModelProviderResponse> toggle(
            @Parameter(description = "模型服务商ID") @PathVariable Long id) {
        ModelProviderResponse response = modelProviderService.toggle(id);
        return Result.success(response);
    }

    /**
     * 删除模型服务商
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型服务商", description = "删除指定的模型服务商")
    public Result<Void> delete(
            @Parameter(description = "模型服务商ID") @PathVariable Long id) {
        modelProviderService.removeProviderById(id);
        return Result.success();
    }

    /**
     * 连接测试
     */
    @PostMapping("/{id}/connectivity-test")
    @Operation(summary = "连接测试", description = "测试模型服务商连接是否正常")
    public Result<Boolean> connectivityTest(
            @Parameter(description = "模型服务商ID") @PathVariable Long id) {
        boolean result = modelProviderService.connectivityTest(id);
        return Result.success(result);
    }
}
