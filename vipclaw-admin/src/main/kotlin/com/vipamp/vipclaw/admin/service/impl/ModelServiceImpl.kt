package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageHelper
import com.vipamp.vipclaw.admin.dto.ModelCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelResponse
import com.vipamp.vipclaw.admin.dto.ModelUpdateRequest
import com.vipamp.vipclaw.admin.dto.Page
import com.vipamp.vipclaw.admin.entity.Model
import com.vipamp.vipclaw.admin.entity.SysJob
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.ModelMapper
import com.vipamp.vipclaw.admin.mapper.ModelProviderMapper
import com.vipamp.vipclaw.admin.service.ModelService
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils.hasText
import java.time.LocalDateTime

/**
 * 模型服务实现类
 *
 * @author vipamp
 * @since 2026-03-13
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
        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // 将 tags 字符串转换为 List
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
        // 检查供应商是否存在
        val provider = modelProviderMapper.selectById(request.providerId)
            ?: throw BizException("模型供应商不存在")

        // 检查同一供应商下 name 是否已存在
        if (modelMapper.countByProviderIdAndName(request.providerId, request.name) > 0) {
            throw BizException("该模型名称在当前供应商下已存在")
        }

        // 检查同一供应商下 model_name 是否已存在
        if (modelMapper.countByProviderIdAndModelName(request.providerId, request.modelName) > 0) {
            throw BizException("该模型标识在当前供应商下已存在")
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

        // 设置创建人
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        model.creator = currentUsername
        return this.modelMapper.insert(model) > 0
    }

    override fun updateModel(id: Long, request: ModelUpdateRequest): Boolean {
        val model = modelMapper.selectById(id)
            ?: throw BizException("模型不存在")

        // 如果修改了供应商,检查是否存在
        if (request.providerId != null && request.providerId != model.providerId) {
            modelProviderMapper.selectById(request.providerId)
                ?: throw BizException("模型供应商不存在")
        }

        // 如果修改了 name,检查是否与其他模型冲突
        if (request.name != null && request.name != model.name) {
            if (modelMapper.countByProviderIdAndName(model.providerId, request.name) > 0) {
                throw BizException("该模型名称在当前供应商下已存在")
            }
            model.name = request.name
        }

        // 如果修改了 modelName,检查是否与其他模型冲突
        if (request.modelName != null && request.modelName != model.modelName) {
            if (modelMapper.countByProviderIdAndModelName(model.providerId, request.modelName) > 0) {
                throw BizException("该模型标识在当前供应商下已存在")
            }
            model.modelName = request.modelName
        }

        // 部分更新:只更新非 null 字段
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
            ?: throw BizException("模型不存在")

        // 如果要启用模型，检查供应商是否启用
        if (status == 1) {
            val provider = modelProviderMapper.selectById(model.providerId)
                ?: throw BizException("模型供应商不存在")
            if (provider.status == 0) {
                throw BizException("供应商已禁用，无法启用模型")
            }
        }

        return this.modelMapper.updateStatus(id, status) > 0
    }

    override fun toggleModel(id: Long, status: Int): Boolean = updateStatus(id, status)

    override fun deleteModel(id: Long): Boolean {
        log.info("删除模型，id: {}", id)
        val model = modelMapper.selectById(id)
            ?: throw BizException("模型不存在")

        // 逻辑删除：设置 active = 0
        val res = modelMapper.deleteById(id) > 0
        log.info("模型删除成功，id: {}", id)
        return res
    }

    override fun convertToResponse(model: Model): ModelResponse {
        val response = ModelResponse.fromEntity(model)
        // 填充供应商名称
        model.providerId.let { pid ->
            val provider = modelProviderMapper.selectById(pid)
            provider?.let {
                response.providerName = it.name
            }
        }
        return response
    }
}
