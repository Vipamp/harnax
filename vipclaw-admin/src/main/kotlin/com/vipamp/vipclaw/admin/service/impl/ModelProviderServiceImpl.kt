package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.admin.dto.ModelProviderCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelProviderResponse
import com.vipamp.vipclaw.admin.dto.ModelProviderUpdateRequest
import com.vipamp.vipclaw.admin.dto.ModelStatsInfo
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.entity.Agent
import com.vipamp.vipclaw.admin.entity.ModelProvider
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.ModelMapper
import com.vipamp.vipclaw.admin.mapper.ModelProviderMapper
import com.vipamp.vipclaw.admin.service.ModelProviderService
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

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
    private val modelProviderMapper: ModelProviderMapper,
) : ModelProviderService {

    private val log = LoggerFactory.getLogger(ModelProviderServiceImpl::class.java)

    override fun page(name: String?, type: String?, status: Int?, isPublic: Int?, pageNum: Int, pageSize: Int): Page<ModelProvider> {
        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        PageHelper.startPage<Agent>(pageNum, pageSize)
        return Page.fromPageInfo(modelProviderMapper.selectModelProviderList(name, type, status, isPublic, currentUsername))
    }

    override fun getModelProvider(id: Long): ModelProvider? = this.modelProviderMapper.selectById(id)

    override fun createModelProvider(request: ModelProviderCreateRequest): Boolean {
        // 检查供应商名称是否已存在
        if (modelProviderMapper.countByName(request.name) > 0) {
            throw BizException("供应商名称已存在")
        }

        val modelProvider = ModelProvider()
        modelProvider.type = request.type
        modelProvider.name = request.name
        modelProvider.description = request.description
        modelProvider.apiKey = request.apiKey // 允许为 null
        modelProvider.baseUrl = request.baseUrl // 允许为 null
        modelProvider.isPublic = request.isPublic ?: 1
        modelProvider.status = 1 // 默认启用
        modelProvider.active = 1 // 默认正常

        // 设置创建人
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        modelProvider.creator = currentUsername
        modelProvider.createTime = LocalDateTime.now()
        modelProvider.updateTime = LocalDateTime.now()

        return this.modelProviderMapper.insert(modelProvider) == 1
    }

    override fun updateModelProvider(id: Long, request: ModelProviderUpdateRequest): Boolean {
        val modelProvider = modelProviderMapper.selectById(id)
            ?: throw BizException("供应商不存在")

        // 如果修改了名称，检查是否重复
        if (!request.name.isNullOrBlank() && request.name != modelProvider.name) {
            if (modelProviderMapper.countByName(request.name) > 0) {
                throw BizException("供应商名称已存在")
            }
            modelProvider.name = request.name
        }

        // 如果修改了类型，直接更新（类型不校验唯一性）
        if (!request.type.isNullOrBlank() && request.type != modelProvider.type) {
            modelProvider.type = request.type
        }

        // 更新描述字段
        if (request.description != null) {
            modelProvider.description = request.description
        }

        // 更新其他字段（只更新非 null 字段）
        request.apiKey?.let { apiKey ->
            // 如果 API Key 不为空且不是脱敏格式，则更新
            if (apiKey.isNotBlank() && !apiKey.contains("****")) {
                modelProvider.apiKey = apiKey
            }
        }
        request.baseUrl?.let { modelProvider.baseUrl = it }
        request.isPublic?.let { modelProvider.isPublic = it }

        modelProvider.updateTime = LocalDateTime.now()
        return this.modelProviderMapper.updateById(modelProvider) > 0
    }

    override fun updateStatus(id: Long, status: Int): Boolean {
        val modelProvider = modelProviderMapper.selectById(id)
            ?: throw BizException("模型服务商不存在")

        // 如果要禁用，检查是否有启用的模型
        if (modelProvider.status == 1 && status == 0) {
            if (modelMapper.countActiveModelsByProviderId(id) > 0) {
                throw BizException("该服务商下有启用的模型，无法禁用")
            }
        }

        return this.modelProviderMapper.updateStatus(id, status) > 0
    }

    override fun toggleModelProvider(id: Long, status: Int): Boolean = updateStatus(id, status)

    override fun deleteModelProvider(id: Long): Boolean {
        // 检查是否有启用的模型
        if (modelMapper.countActiveModelsByProviderId(id) > 0) {
            throw BizException("该服务商下有启用的模型，无法删除")
        }
        return modelProviderMapper.deleteById(id) > 0
    }

    override fun convertToResponse(it: ModelProvider): ModelProviderResponse = ModelProviderResponse.fromEntity(it)

    override fun getModelStats(providerId: Long): ModelStatsInfo {
        val totalModels = modelMapper.countModelsByProviderId(providerId)
        val enabledModels = modelMapper.countActiveModelsByProviderId(providerId)
        val disabledModels = modelMapper.countDisabledModelsByProviderId(providerId)
        return ModelStatsInfo(totalModels, enabledModels, disabledModels)
    }

    override fun connectivityTest(id: Long): Boolean {
        val modelProvider = this.modelProviderMapper.selectById(id)
            ?: throw BizException("模型服务商不存在")

        // TODO: 实现实际的连接测试逻辑
        // 目前直接返回 true
        return true
    }
}
