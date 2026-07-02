package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.ModelCreateRequest
import com.agnetix.harnax.admin.dto.ModelResponse
import com.agnetix.harnax.admin.dto.ModelUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.ModelService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.UserContextUtil
import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.ModelProviderMapper
import com.github.pagehelper.PageHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils.hasText
import java.time.LocalDateTime

/**
 * 模型服务实现
 */
@Service
class ModelServiceImpl(
    private val messageUtil: MessageUtil,
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
        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // 将标签字符串转为列表
        val tagsList = if (hasText(tags)) {
            tags!!.split(",").map { it.trim() }
        } else {
            null
        }

        PageHelper.startPage<Model>(pageNum, pageSize)
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
        // 检查服务商是否存在
        val provider = modelProviderMapper.selectById(request.providerId)
            ?: throw BizException(messageUtil.getMessage("error.model.provider.notfound"))

        // 检查同一服务商下名称是否已存在
        if (modelMapper.countByProviderIdAndName(request.providerId, request.name) > 0) {
            throw BizException(messageUtil.getMessage("error.model.name_exists"))
        }

        // 检查同一服务商下模型标识是否已存在
        if (modelMapper.countByProviderIdAndModelName(request.providerId, request.modelName) > 0) {
            throw BizException(messageUtil.getMessage("error.model.model_name_exists"))
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

        // 设置租户ID
        model.tenantId = TenantContext.getTenantId() ?: 1

        // 设置创建者
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        model.creator = currentUsername
        return this.modelMapper.insert(model) > 0
    }

    override fun updateModel(id: Long, request: ModelUpdateRequest): Boolean {
        val model = modelMapper.selectById(id)
            ?: throw BizException(messageUtil.getMessage("error.model.notfound"))

        // 如果修改了服务商，检查服务商是否存在
        if (request.providerId != null && request.providerId != model.providerId) {
            modelProviderMapper.selectById(request.providerId)
                ?: throw BizException(messageUtil.getMessage("error.model.provider.notfound"))
        }

        // 如果修改了名称，检查是否与其他模型冲突
        if (request.name != null && request.name != model.name) {
            if (modelMapper.countByProviderIdAndName(model.providerId, request.name) > 0) {
                throw BizException(messageUtil.getMessage("error.model.name_exists"))
            }
            model.name = request.name
        }

        // 如果修改了模型标识，检查是否与其他模型冲突
        if (request.modelName != null && request.modelName != model.modelName) {
            if (modelMapper.countByProviderIdAndModelName(model.providerId, request.modelName) > 0) {
                throw BizException(messageUtil.getMessage("error.model.model_name_exists"))
            }
            model.modelName = request.modelName
        }

        // 部分更新：仅更新非空字段
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
            ?: throw BizException(messageUtil.getMessage("error.model.notfound"))

        // 启用模型时检查服务商是否已启用
        if (status == 1) {
            val provider = modelProviderMapper.selectById(model.providerId)
                ?: throw BizException(messageUtil.getMessage("error.model.provider.notfound"))
            if (provider.status == 0) {
                throw BizException(messageUtil.getMessage("error.model.provider_disabled"))
            }
        }

        return this.modelMapper.updateStatus(id, status) > 0
    }

    override fun toggleModel(id: Long, status: Int): Boolean = updateStatus(id, status)

    override fun deleteModel(id: Long): Boolean {
        log.info("删除模型, id: {}", id)
        val model = modelMapper.selectById(id)
            ?: throw BizException(messageUtil.getMessage("error.model.notfound"))

        // 逻辑删除：设置 active = 0
        val res = modelMapper.deleteById(id) > 0
        log.info("模型删除成功, id: {}", id)
        return res
    }

    override fun convertToResponse(model: Model): ModelResponse {
        val response = ModelResponse.fromEntity(model)
        // 填充服务商名称
        model.providerId.let { pid ->
            val provider = modelProviderMapper.selectById(pid)
            provider?.let {
                response.providerName = it.name
            }
        }
        return response
    }
}
