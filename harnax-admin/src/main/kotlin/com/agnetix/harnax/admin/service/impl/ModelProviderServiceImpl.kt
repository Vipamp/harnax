package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.ModelProviderCreateRequest
import com.agnetix.harnax.admin.dto.ModelProviderResponse
import com.agnetix.harnax.admin.dto.ModelProviderUpdateRequest
import com.agnetix.harnax.admin.dto.ModelStatsInfo
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.entity.Agent
import com.agnetix.harnax.admin.entity.ModelProvider
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.mapper.ModelMapper
import com.agnetix.harnax.admin.mapper.ModelProviderMapper
import com.agnetix.harnax.admin.service.ModelProviderService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * 模型服务商服务实现
 */
@Service
class ModelProviderServiceImpl(
    private val messageUtil: MessageUtil,
    private val modelMapper: ModelMapper,
    private val jwtUtil: JwtUtil,
    private val modelProviderMapper: ModelProviderMapper,
) : ModelProviderService {

    private val log = LoggerFactory.getLogger(ModelProviderServiceImpl::class.java)

    override fun page(name: String?, type: String?, status: Int?, isPublic: Int?, pageNum: Int, pageSize: Int): Page<ModelProvider> {
        // Get current user
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        PageHelper.startPage<Agent>(pageNum, pageSize)
        return Page.fromPageInfo(modelProviderMapper.selectModelProviderList(name, type, status, isPublic, currentUsername))
    }

    override fun getModelProvider(id: Long): ModelProvider? = this.modelProviderMapper.selectById(id)

    override fun createModelProvider(request: ModelProviderCreateRequest): Boolean {
        // 检查服务商名称是否已存在
        if (modelProviderMapper.countByName(request.name) > 0) {
            throw BizException(messageUtil.getMessage("error.model.provider.name_exists"))
        }

        val modelProvider = ModelProvider()
        modelProvider.type = request.type
        modelProvider.name = request.name
        modelProvider.description = request.description
        modelProvider.apiKey = request.apiKey // 允许为空
        modelProvider.baseUrl = request.baseUrl // 允许为空
        modelProvider.isPublic = request.isPublic ?: 1
        modelProvider.status = 1 // 默认启用
        modelProvider.active = 1 // 默认启用

        // 设置创建者
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        modelProvider.creator = currentUsername
        modelProvider.createTime = LocalDateTime.now()
        modelProvider.updateTime = LocalDateTime.now()

        return this.modelProviderMapper.insert(modelProvider) == 1
    }

    override fun updateModelProvider(id: Long, request: ModelProviderUpdateRequest): Boolean {
        val modelProvider = modelProviderMapper.selectById(id)
            ?: throw BizException(messageUtil.getMessage("error.model.provider.notfound"))

        // 如果修改了名称，检查是否重复
        if (!request.name.isNullOrBlank() && request.name != modelProvider.name) {
            if (modelProviderMapper.countByName(request.name) > 0) {
                throw BizException(messageUtil.getMessage("error.model.provider.name_exists"))
            }
            modelProvider.name = request.name
        }

        // 如果修改了类型，直接更新（类型不需要唯一性校验）
        if (!request.type.isNullOrBlank() && request.type != modelProvider.type) {
            modelProvider.type = request.type
        }

        // 更新描述字段
        if (request.description != null) {
            modelProvider.description = request.description
        }

        // 更新其他字段（仅更新非空字段）
        request.apiKey?.let { apiKey ->
            // API Key 非空且非脱敏格式时才更新
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
            ?: throw BizException(messageUtil.getMessage("error.model.provider.notfound"))

        // 停用时检查是否存在启用的模型
        if (modelProvider.status == 1 && status == 0) {
            if (modelMapper.countActiveModelsByProviderId(id) > 0) {
                throw BizException(messageUtil.getMessage("error.model.provider.cannot_disable"))
            }
        }

        return this.modelProviderMapper.updateStatus(id, status) > 0
    }

    override fun toggleModelProvider(id: Long, status: Int): Boolean = updateStatus(id, status)

    override fun deleteModelProvider(id: Long): Boolean {
        // 检查是否存在启用的模型
        if (modelMapper.countActiveModelsByProviderId(id) > 0) {
            throw BizException(messageUtil.getMessage("error.model.provider.cannot_delete"))
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
            ?: throw BizException(messageUtil.getMessage("error.model.provider.notfound"))

        // TODO: 实现真实的连接测试逻辑
        // 目前直接返回 true
        return true
    }
}
