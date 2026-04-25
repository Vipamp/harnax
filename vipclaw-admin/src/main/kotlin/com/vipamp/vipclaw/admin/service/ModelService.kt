package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.admin.dto.ModelCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelResponse
import com.vipamp.vipclaw.admin.dto.ModelUpdateRequest
import com.vipamp.vipclaw.admin.entity.Model
import com.vipamp.vipclaw.common.page.Page

/**
 * 模型服务接口
 *
 * @author vipamp
 * @since 2026-03-13
 */
interface ModelService {

    /**
     * 分页查询模型
     *
     * @param name       名称
     * @param providerId 供应商ID
     * @param modelType  模型类型
     * @param status     状态
     * @param tags       标签筛选（支持多个，如：internet,reasoning,tool,mcp,vision）
     * @param minPrice   最低价格
     * @param maxPrice   最高价格
     * @param pageNum    当前页码
     * @param pageSize   每页大小
     * @return 分页结果
     */
    fun page(
        name: String?,
        providerId: Long?,
        modelType: String?,
        status: Int?,
        tags: String?,
        minPrice: Double?,
        maxPrice: Double?,
        pageNum: Int,
        pageSize: Int
    ): Page<Model>

    /**
     * 获取模型详情
     *
     * @param id ID
     * @return 模型实体
     */
    fun getModel(id: Long): Model?

    /**
     * 创建模型
     *
     * @param request 创建请求
     * @return 模型响应
     */
    fun createModel(request: ModelCreateRequest): Boolean

    /**
     * 更新模型
     *
     * @param id      ID
     * @param request 更新请求
     * @return 模型响应
     */
    fun updateModel(id: Long, request: ModelUpdateRequest): Boolean

    /**
     * 更新模型状态
     *
     * @param id     ID
     * @param status 状态（0:禁用，1:启用）
     * @return 更新结果
     */
    fun updateStatus(id: Long, status: Int): Boolean

    /**
     * 切换模型状态
     *
     * @param id     ID
     * @param status 状态（0:禁用，1:启用）
     * @return 更新结果
     */
    fun toggleModel(id: Long, status: Int): Boolean

    /**
     * 删除模型（逻辑删除）
     *
     * @param id ID
     * @return 删除结果
     */
    fun deleteModel(id: Long): Boolean

    /**
     * 将模型实体转换为模型响应
     *
     * @param model 模型实体
     * @return 模型响应
     */
    fun convertToResponse(model: Model): ModelResponse
}
