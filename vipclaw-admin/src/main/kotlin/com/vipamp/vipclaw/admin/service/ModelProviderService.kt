package com.vipamp.vipclaw.admin.service

import com.vipamp.vipclaw.common.page.Page
import com.vipamp.vipclaw.admin.dto.ModelProviderCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelProviderResponse
import com.vipamp.vipclaw.admin.dto.ModelProviderUpdateRequest
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
     * @param page     分页对象
     * @param name     服务商名称
     * @param status   状态
     * @param isPublic 是否公开
     * @return 分页结果
     */
    fun page(page: Page<ModelProvider>, name: String?, status: Int?, isPublic: Int?): Page<ModelProviderResponse>

    /**
     * 获取模型服务商详情
     *
     * @param id ID
     * @return 模型服务商响应
     */
    fun getDetail(id: Long): ModelProviderResponse

    /**
     * 创建模型服务商
     *
     * @param request 创建请求
     * @return 模型服务商响应
     */
    fun create(request: ModelProviderCreateRequest): ModelProviderResponse

    /**
     * 更新模型服务商
     *
     * @param id      ID
     * @param request 更新请求
     * @return 模型服务商响应
     */
    fun update(id: Long, request: ModelProviderUpdateRequest): ModelProviderResponse

    /**
     * 切换模型服务商状态
     *
     * @param id ID
     * @return 模型服务商响应
     */
    fun toggle(id: Long): ModelProviderResponse

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
    fun removeProviderById(id: Long): Boolean
}
