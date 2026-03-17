package com.vipamp.vipclaw.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.vipamp.vipclaw.admin.dto.ModelCreateRequest;
import com.vipamp.vipclaw.admin.dto.ModelResponse;
import com.vipamp.vipclaw.admin.dto.ModelUpdateRequest;
import com.vipamp.vipclaw.admin.entity.Model;
import com.vipamp.vipclaw.admin.service.ModelService;
import com.vipamp.vipclaw.admin.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 模型控制器
 *
 * @author vipamp
 * @since 2026-03-13
 */
@RestController
@RequestMapping("/models")
@Tag(name = "模型管理", description = "模型的增删改查接口")
public class ModelController {

    @Autowired
    private ModelService modelService;

    /**
     * 分页查询模型
     */
    @GetMapping("/page")
    @Operation(summary = "分页查询模型", description = "分页查询模型列表")
    public Result<Page<ModelResponse>> page(
            @Parameter(description = "当前页码") @RequestParam(name = "current", defaultValue = "1") Integer current,
            @Parameter(description = "每页条数") @RequestParam(name = "pageSize", defaultValue = "10") Integer pageSize,
            @Parameter(description = "名称") @RequestParam(name = "name", required = false) String name,
            @Parameter(description = "供应商ID") @RequestParam(name = "providerId", required = false) Long providerId,
            @Parameter(description = "模型类型") @RequestParam(name = "modelType", required = false) String modelType,
            @Parameter(description = "状态") @RequestParam(name = "status", required = false) Integer status,
            @Parameter(description = "标签筛选（支持多个，如：internet,reasoning,tool,mcp,vision）") @RequestParam(name = "tags", required = false) String tags,
            @Parameter(description = "最低价格") @RequestParam(name = "minPrice", required = false) Double minPrice,
            @Parameter(description = "最高价格") @RequestParam(name = "maxPrice", required = false) Double maxPrice) {
        
        Page<Model> page = new Page<>(current, pageSize);
        Page<ModelResponse> result = modelService.page(page, name, providerId, modelType, status, tags, minPrice, maxPrice);
        return Result.success(result);
    }

    /**
     * 获取模型详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取模型详情", description = "根据 ID 获取模型详情")
    public Result<ModelResponse> getDetail(
            @Parameter(description = "模型ID") @PathVariable(name = "id") Long id) {
        ModelResponse response = modelService.getDetail(id);
        return Result.success(response);
    }

    /**
     * 创建模型
     */
    @PostMapping
    @Operation(summary = "创建模型", description = "创建新的模型")
    public Result<ModelResponse> create(
            @Valid @RequestBody ModelCreateRequest request) {
        ModelResponse response = modelService.create(request);
        return Result.success(response);
    }

    /**
     * 更新模型
     */
    @PutMapping("/update/{id}")
    @Operation(summary = "更新模型", description = "更新模型信息")
    public Result<ModelResponse> update(
            @Parameter(description = "模型ID") @PathVariable(name = "id") Long id,
            @Valid @RequestBody ModelUpdateRequest request) {
        request.setId(id);
        ModelResponse response = modelService.update(id, request);
        return Result.success(response);
    }

    /**
     * 切换模型状态
     */
    @PutMapping("/toggle/{id}")
    @Operation(summary = "切换模型状态", description = "启用/禁用模型")
    public Result<ModelResponse> toggle(
            @Parameter(description = "模型ID") @PathVariable(name = "id") Long id) {
        ModelResponse response = modelService.toggle(id);
        return Result.success(response);
    }

    /**
     * 删除模型
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型", description = "删除指定的模型")
    public Result<Void> delete(
            @Parameter(description = "模型ID") @PathVariable(name = "id") Long id) {
        modelService.removeById(id);
        return Result.success();
    }
}
