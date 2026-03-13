package com.vipclaw.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.vipclaw.admin.dto.ModelCreateRequest;
import com.vipclaw.admin.dto.ModelResponse;
import com.vipclaw.admin.dto.ModelUpdateRequest;
import com.vipclaw.admin.entity.Model;

/**
 * 模型服务接口
 *
 * @author vipamp
 * @since 2026-03-13
 */
public interface ModelService extends IService<Model> {

    /**
     * 分页查询模型
     *
     * @param page       分页对象
     * @param name       名称
     * @param providerId 供应商ID
     * @param modelType  模型类型
     * @param status     状态
     * @param tags       标签筛选（支持多个，如：internet,reasoning,tool,mcp,vision）
     * @param minPrice   最低价格
     * @param maxPrice   最高价格
     * @return 分页结果
     */
    Page<ModelResponse> page(Page<Model> page, String name, Long providerId, String modelType, Integer status, String tags, Double minPrice, Double maxPrice);

    /**
     * 获取模型详情
     *
     * @param id ID
     * @return 模型响应
     */
    ModelResponse getDetail(Long id);

    /**
     * 创建模型
     *
     * @param request 创建请求
     * @return 模型响应
     */
    ModelResponse create(ModelCreateRequest request);

    /**
     * 更新模型
     *
     * @param id      ID
     * @param request 更新请求
     * @return 模型响应
     */
    ModelResponse update(Long id, ModelUpdateRequest request);

    /**
     * 切换模型状态
     *
     * @param id ID
     * @return 模型响应
     */
    ModelResponse toggle(Long id);
}
