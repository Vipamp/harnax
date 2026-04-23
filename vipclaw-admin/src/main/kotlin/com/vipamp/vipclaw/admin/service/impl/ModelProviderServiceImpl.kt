package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.common.page.Page
import java.time.LocalDateTime
import com.vipamp.vipclaw.admin.dto.ModelProviderCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelProviderResponse
import com.vipamp.vipclaw.admin.dto.ModelProviderUpdateRequest
import com.vipamp.vipclaw.admin.entity.Model
import com.vipamp.vipclaw.admin.entity.ModelProvider
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.ModelMapper
import com.vipamp.vipclaw.admin.mapper.ModelProviderMapper
import com.vipamp.vipclaw.admin.service.ModelProviderService
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import kotlin.math.min

/**
 * 模型服务商服务实现类
 *
 * @author vipamp
 * @since 2026-03-13
 */
@Service
class ModelProviderServiceImpl(
    private val modelMapper: ModelMapper,
    private val jwtUtil: JwtUtil,
    private val modelProviderMapper: ModelProviderMapper
) : ModelProviderService {

    private val log = LoggerFactory.getLogger(ModelProviderServiceImpl::class.java)

    override fun page(page: Page<ModelProvider>, name: String?, status: Int?, isPublic: Int?): Page<ModelProviderResponse> {
        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // 使用 MyBatis 原生查询
        val allProviders = modelProviderMapper.selectModelProviderList(name, status, isPublic, currentUsername)

        // 手动分页
        val fromIndex = ((page.current - 1) * page.size).toInt()
        val toIndex = min(fromIndex + page.size.toInt(), allProviders.size)
        
        val records = if (fromIndex < allProviders.size) {
            allProviders.subList(fromIndex, toIndex)
        } else {
            emptyList()
        }

        // 转换为响应对象
        val responsePage = Page<ModelProviderResponse>(page.current, page.size, allProviders.size.toLong())
        responsePage.records = records.map { ModelProviderResponse.fromEntity(it) }

        return responsePage
    }

    override fun getDetail(id: Long): ModelProviderResponse {
        val modelProvider = this.modelProviderMapper.selectById(id)
            ?: throw BizException("模型服务商不存在")
        return ModelProviderResponse.fromEntity(modelProvider)
    }

    override fun create(request: ModelProviderCreateRequest): ModelProviderResponse {
        // 检查名称是否已存在
        if (modelProviderMapper.countByName(request.name) > 0) {
            throw BizException("供应商名称已存在")
        }

        val modelProvider = ModelProvider()
        modelProvider.name = request.name
        modelProvider.displayName = request.displayName
        modelProvider.apiKey = request.apiKey  // 允许为 null
        modelProvider.baseUrl = request.baseUrl  // 允许为 null
        modelProvider.isPublic = request.isPublic ?: 1
        modelProvider.status = 1 // 默认启用
        modelProvider.active = 1 // 默认正常
        
        // 设置创建人
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        modelProvider.creator = currentUsername
        modelProvider.createTime = LocalDateTime.now()
        modelProvider.updateTime = LocalDateTime.now()

        this.modelProviderMapper.insert(modelProvider)
        return ModelProviderResponse.fromEntity(modelProvider)
    }

    override fun update(id: Long, request: ModelProviderUpdateRequest): ModelProviderResponse {
        val modelProvider = modelProviderMapper.selectActiveById(id)
            ?: throw BizException("供应商不存在")

        // 如果修改了名称，检查是否重复
        if (!request.name.isNullOrBlank() && request.name != modelProvider.name) {
            if (modelProviderMapper.countByName(request.name) > 0) {
                throw BizException("供应商名称已存在")
            }
            modelProvider.name = request.name
        }

        // 更新其他字段（只更新非 null 字段）
        if (!request.displayName.isNullOrBlank()) {
            modelProvider.displayName = request.displayName
        }
        request.apiKey?.let { apiKey ->
            // 如果 API Key 不为空且不是脱敏格式，则更新
            if (apiKey.isNotBlank() && !apiKey.contains("****")) {
                modelProvider.apiKey = apiKey
            }
        }
        request.baseUrl?.let { modelProvider.baseUrl = it }
        request.isPublic?.let { modelProvider.isPublic = it }

        modelProvider.updateTime = LocalDateTime.now()
        this.modelProviderMapper.updateById(modelProvider)
        
        return ModelProviderResponse.fromEntity(modelProvider)
    }

    override fun toggle(id: Long): ModelProviderResponse {
        val modelProvider = modelProviderMapper.selectActiveById(id)
            ?: throw BizException("模型服务商不存在")

        // 如果要禁用，检查是否有启用的模型
        if (modelProvider.status == 1) {
            if (modelMapper.countActiveModelsByProviderId(id) > 0) {
                throw BizException("该服务商下有启用的模型，无法禁用")
            }
        }

        // 切换状态
        modelProvider.status = if (modelProvider.status == 1) 0 else 1
        this.modelProviderMapper.updateById(modelProvider) > 0

        return ModelProviderResponse.fromEntity(modelProvider)
    }

    override fun removeProviderById(id: Long): Boolean {
        // 检查是否有启用的模型
        if (modelMapper.countActiveModelsByProviderId(id) > 0) {
            throw BizException("该服务商下有启用的模型，无法删除")
        }
        return modelProviderMapper.deleteById(id) > 0
    }

    override fun connectivityTest(id: Long): Boolean {
        val modelProvider = this.modelProviderMapper.selectById(id)
            ?: throw BizException("模型服务商不存在")

        // TODO: 实现实际的连接测试逻辑
        // 目前直接返回 true
        return true
    }
}
