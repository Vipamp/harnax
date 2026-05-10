package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.ModelProviderCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelProviderResponse
import com.vipamp.vipclaw.admin.dto.ModelProviderUpdateRequest
import com.vipamp.vipclaw.admin.dto.ModelStatsInfo
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.entity.ModelProvider

/**
 * 模型服务商服务接口
 *
 * @author vipamp
 * @since 2026-03-13
 */
interface ModelProviderService {

    /**
     * 分页查询模型服务商
     *
     * @param name     服务商名称
     * @param type     服务商类型
     * @param status   状态
     * @param isPublic 是否公开
     * @param pageNum  当前页码
     * @param pageSize 每页大小
     * @return 分页结果
     */
    fun page(name: String?, type: String?, status: Int?, isPublic: Int?, pageNum: Int, pageSize: Int): Page<ModelProvider>

    /**
     * 获取模型服务商详情
     *
     * @param id ID
     * @return 模型服务商响应
     */
    fun getModelProvider(id: Long): ModelProvider?

    /**
     * 创建模型服务商
     *
     * @param request 创建请求
     * @return 模型服务商响应
     */
    fun createModelProvider(request: ModelProviderCreateRequest): Boolean

    /**
     * 更新模型服务商
     *
     * @param id      ID
     * @param request 更新请求
     * @return 更新结果
     */
    fun updateModelProvider(id: Long, request: ModelProviderUpdateRequest): Boolean

    /**
     * 更新模型服务商状态
     *
     * @param id     ID
     * @param status 状态（0:禁用，1:启用）
     * @return 更新结果
     */
    fun updateStatus(id: Long, status: Int): Boolean

    /**
     * 切换模型服务商状态
     *
     * @param id     ID
     * @param status 状态（0:禁用，1:启用）
     * @return 更新结果
     */
    fun toggleModelProvider(id: Long, status: Int): Boolean

    /**
     * 连接测试
     *
     * @param id ID
     * @return 是否连接成功
     */
    fun connectivityTest(id: Long): Boolean

    /**
     * 删除模型服务商（带校验）
     *
     * @param id ID
     * @return 是否删除成功
     */
    fun deleteModelProvider(id: Long): Boolean

    /**
     * 将模型服务商转换为响应对象
     *
     * @param modelProvider 模型服务商
     * @return 模型服务商响应
     */
    fun convertToResponse(modelProvider: ModelProvider): ModelProviderResponse

    /**
     * 获取服务商的模型统计信息
     *
     * @param providerId 服务商ID
     * @return 模型统计信息
     */
    fun getModelStats(providerId: Long): ModelStatsInfo
}
