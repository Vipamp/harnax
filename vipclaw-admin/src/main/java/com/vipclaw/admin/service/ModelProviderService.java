package com.vipclaw.admin.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.vipclaw.admin.dto.ModelProviderCreateRequest;
import com.vipclaw.admin.dto.ModelProviderResponse;
import com.vipclaw.admin.dto.ModelProviderUpdateRequest;
import com.vipclaw.admin.entity.ModelProvider;

/**
 * 模型服务商服务接口
 *
 * @author vipamp
 * @since 2026-03-13
 */
public interface ModelProviderService extends IService<ModelProvider> {

    /**
     * 分页查询模型服务商
     *
     * @param page     分页对象
     * @param name     服务商名称
     * @param status   状态
     * @return 分页结果
     */
    Page<ModelProviderResponse> page(Page<ModelProvider> page, String name, Integer status);

    /**
     * 获取模型服务商详情
     *
     * @param id ID
     * @return 模型服务商响应
     */
    ModelProviderResponse getDetail(Long id);

    /**
     * 创建模型服务商
     *
     * @param request 创建请求
     * @return 模型服务商响应
     */
    ModelProviderResponse create(ModelProviderCreateRequest request);

    /**
     * 更新模型服务商
     *
     * @param id      ID
     * @param request 更新请求
     * @return 模型服务商响应
     */
    ModelProviderResponse update(Long id, ModelProviderUpdateRequest request);

    /**
     * 切换模型服务商状态
     *
     * @param id ID
     * @return 模型服务商响应
     */
    ModelProviderResponse toggle(Long id);

    /**
     * 连接测试
     *
     * @param id ID
     * @return 是否连接成功
     */
    boolean connectivityTest(Long id);

    /**
     * 删除模型服务商（带校验）
     *
     * @param id ID
     * @return 是否删除成功
     */
    boolean removeProviderById(Long id);
}
