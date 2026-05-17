package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.ModelCreateRequest
import com.agnetix.harnax.admin.dto.ModelResponse
import com.agnetix.harnax.admin.dto.ModelUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.entity.Model
import com.agnetix.harnax.admin.entity.SysJob
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.mapper.ModelMapper
import com.agnetix.harnax.admin.mapper.ModelProviderMapper
import com.agnetix.harnax.admin.service.ModelService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils.hasText
import java.time.LocalDateTime

/**
 * Model service implementation
 */
@Service
class ModelServiceImpl(
    private val modelProviderMapper: ModelProviderMapper,
    private val jwtUtil: JwtUtil,
    private val modelMapper: ModelMapper,
) : ModelService {

    private val log = LoggerFactory.getLogger(ModelServiceImpl::class.java)

    override fun page(
        name: String?,
        providerId: Long?,
        modelType: String?,
        status: Int?,
        tags: String?,
        minPrice: Double?,
        maxPrice: Double?,
        pageNum: Int,
        pageSize: Int,
    ): Page<Model> {
        // Get current user
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // Convert tags string to List
        val tagsList = if (hasText(tags)) {
            tags!!.split(",").map { it.trim() }
        } else {
            null
        }

        PageHelper.startPage<SysJob>(pageNum, pageSize)
        return Page.fromPageInfo(
            modelMapper.selectModelList(
                name,
                providerId,
                modelType,
                status,
                tagsList,
                minPrice,
                maxPrice,
                currentUsername,
            ),
        )
    }

    override fun getModel(id: Long): Model? = this.modelMapper.selectById(id)

    override fun createModel(request: ModelCreateRequest): Boolean {
        // Check if provider exists
        val provider = modelProviderMapper.selectById(request.providerId)
            ?: throw BizException("Model provider not found")

        // Check if name already exists under the same provider
        if (modelMapper.countByProviderIdAndName(request.providerId, request.name) > 0) {
            throw BizException("Model name already exists under current provider")
        }

        // Check if model_name already exists under the same provider
        if (modelMapper.countByProviderIdAndModelName(request.providerId, request.modelName) > 0) {
            throw BizException("Model identifier already exists under current provider")
        }

        val model = Model()
        model.name = request.name
        model.modelName = request.modelName
        model.providerId = request.providerId
        model.description = request.description
        model.modelType = request.modelType
        model.supportInternet = request.supportInternet ?: 0
        model.supportReasoning = request.supportReasoning ?: 0
        model.supportTool = request.supportTool ?: 0
        model.supportMcp = request.supportMcp ?: 0
        model.supportVision = request.supportVision ?: 0
        model.price = request.price ?: 0.0
        model.isPublic = request.isPublic ?: 1

        // Set tenant ID
        model.tenantId = TenantContext.getTenantId() ?: 1

        // Set creator
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        model.creator = currentUsername
        return this.modelMapper.insert(model) > 0
    }

    override fun updateModel(id: Long, request: ModelUpdateRequest): Boolean {
        val model = modelMapper.selectById(id)
            ?: throw BizException("Model not found")

        // If provider is modified, check if it exists
        if (request.providerId != null && request.providerId != model.providerId) {
            modelProviderMapper.selectById(request.providerId)
                ?: throw BizException("Model provider not found")
        }

        // If name is modified, check for conflicts with other models
        if (request.name != null && request.name != model.name) {
            if (modelMapper.countByProviderIdAndName(model.providerId, request.name) > 0) {
                throw BizException("Model name already exists under current provider")
            }
            model.name = request.name
        }

        // If modelName is modified, check for conflicts with other models
        if (request.modelName != null && request.modelName != model.modelName) {
            if (modelMapper.countByProviderIdAndModelName(model.providerId, request.modelName) > 0) {
                throw BizException("Model identifier already exists under current provider")
            }
            model.modelName = request.modelName
        }

        // Partial update: only update non-null fields
        request.providerId?.let { model.providerId = it }
        request.description?.let { model.description = it }
        request.modelType?.let { model.modelType = it }
        request.supportInternet?.let { model.supportInternet = it }
        request.supportReasoning?.let { model.supportReasoning = it }
        request.supportTool?.let { model.supportTool = it }
        request.supportMcp?.let { model.supportMcp = it }
        request.supportVision?.let { model.supportVision = it }
        request.price?.let { model.price = it }
        request.isPublic?.let { model.isPublic = it }

        model.updateTime = LocalDateTime.now()
        return this.modelMapper.updateById(model) > 0
    }

    override fun updateStatus(id: Long, status: Int): Boolean {
        val model = this.modelMapper.selectById(id)
            ?: throw BizException("Model not found")

        // If enabling model, check if provider is enabled
        if (status == 1) {
            val provider = modelProviderMapper.selectById(model.providerId)
                ?: throw BizException("Model provider not found")
            if (provider.status == 0) {
                throw BizException("Provider is disabled, cannot enable model")
            }
        }

        return this.modelMapper.updateStatus(id, status) > 0
    }

    override fun toggleModel(id: Long, status: Int): Boolean = updateStatus(id, status)

    override fun deleteModel(id: Long): Boolean {
        log.info("Deleting model, id: {}", id)
        val model = modelMapper.selectById(id)
            ?: throw BizException("Model not found")

        // Logical delete: set active = 0
        val res = modelMapper.deleteById(id) > 0
        log.info("Model deleted successfully, id: {}", id)
        return res
    }

    override fun convertToResponse(model: Model): ModelResponse {
        val response = ModelResponse.fromEntity(model)
        // Fill provider name
        model.providerId.let { pid ->
            val provider = modelProviderMapper.selectById(pid)
            provider?.let {
                response.providerName = it.name
            }
        }
        return response
    }
}
