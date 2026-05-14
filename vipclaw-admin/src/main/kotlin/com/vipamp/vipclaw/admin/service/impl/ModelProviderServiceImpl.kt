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
 * Model provider service implementation
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
        // Get current user
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        PageHelper.startPage<Agent>(pageNum, pageSize)
        return Page.fromPageInfo(modelProviderMapper.selectModelProviderList(name, type, status, isPublic, currentUsername))
    }

    override fun getModelProvider(id: Long): ModelProvider? = this.modelProviderMapper.selectById(id)

    override fun createModelProvider(request: ModelProviderCreateRequest): Boolean {
        // Check if provider name already exists
        if (modelProviderMapper.countByName(request.name) > 0) {
            throw BizException("Provider name already exists")
        }

        val modelProvider = ModelProvider()
        modelProvider.type = request.type
        modelProvider.name = request.name
        modelProvider.description = request.description
        modelProvider.apiKey = request.apiKey // Allow null
        modelProvider.baseUrl = request.baseUrl // Allow null
        modelProvider.isPublic = request.isPublic ?: 1
        modelProvider.status = 1 // Default enabled
        modelProvider.active = 1 // Default active

        // Set creator
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        modelProvider.creator = currentUsername
        modelProvider.createTime = LocalDateTime.now()
        modelProvider.updateTime = LocalDateTime.now()

        return this.modelProviderMapper.insert(modelProvider) == 1
    }

    override fun updateModelProvider(id: Long, request: ModelProviderUpdateRequest): Boolean {
        val modelProvider = modelProviderMapper.selectById(id)
            ?: throw BizException("Provider not found")

        // If name is modified, check for duplicates
        if (!request.name.isNullOrBlank() && request.name != modelProvider.name) {
            if (modelProviderMapper.countByName(request.name) > 0) {
                throw BizException("Provider name already exists")
            }
            modelProvider.name = request.name
        }

        // If type is modified, update directly (type does not require uniqueness check)
        if (!request.type.isNullOrBlank() && request.type != modelProvider.type) {
            modelProvider.type = request.type
        }

        // Update description field
        if (request.description != null) {
            modelProvider.description = request.description
        }

        // Update other fields (only update non-null fields)
        request.apiKey?.let { apiKey ->
            // If API Key is not empty and not in masked format, update it
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
            ?: throw BizException("Model provider not found")

        // If disabling, check if there are enabled models
        if (modelProvider.status == 1 && status == 0) {
            if (modelMapper.countActiveModelsByProviderId(id) > 0) {
                throw BizException("Cannot disable: there are enabled models under this provider")
            }
        }

        return this.modelProviderMapper.updateStatus(id, status) > 0
    }

    override fun toggleModelProvider(id: Long, status: Int): Boolean = updateStatus(id, status)

    override fun deleteModelProvider(id: Long): Boolean {
        // Check if there are enabled models
        if (modelMapper.countActiveModelsByProviderId(id) > 0) {
            throw BizException("Cannot delete: there are enabled models under this provider")
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
            ?: throw BizException("Model provider not found")

        // TODO: Implement actual connectivity test logic
        // Currently returns true directly
        return true
    }
}
