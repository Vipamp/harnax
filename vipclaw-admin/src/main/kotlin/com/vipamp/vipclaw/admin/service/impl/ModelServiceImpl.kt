package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.ModelCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelResponse
import com.vipamp.vipclaw.admin.dto.ModelUpdateRequest
import com.vipamp.vipclaw.admin.entity.Model
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.ModelMapper
import com.vipamp.vipclaw.admin.mapper.ModelProviderMapper
import com.vipamp.vipclaw.admin.service.ModelService
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import com.vipamp.vipclaw.common.page.Page
import org.slf4j.LoggerFactory
import org.springframework.beans.BeanUtils
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils.hasText
import kotlin.math.min

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
    private val modelMapper: ModelMapper
) : ModelService {

    private val log = LoggerFactory.getLogger(ModelServiceImpl::class.java)

    override fun page(
        page: Page<Model>,
        name: String?,
        providerId: Long?,
        modelType: String?,
        status: Int?,
        tags: String?,
        minPrice: Double?,
        maxPrice: Double?
    ): Page<ModelResponse> {
        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // 将 tags 字符串转换为 List
        val tagsList = if (hasText(tags)) {
            tags!!.split(",").map { it.trim() }
        } else {
            null
        }

        // 使用 MyBatis 原生查询
        val allModels = modelMapper.selectModelList(
            name,
            providerId,
            modelType,
            status,
            tagsList,
            minPrice,
            maxPrice,
            currentUsername
        )

        // 手动分页
        val fromIndex = ((page.current - 1) * page.size).toInt()
        val toIndex = min(fromIndex + page.size.toInt(), allModels.size)

        val records = if (fromIndex < allModels.size) {
            allModels.subList(fromIndex, toIndex)
        } else {
            emptyList()
        }

        // 转换为响应对象
        val responsePage = Page<ModelResponse>(page.current, page.size, allModels.size.toLong())
        responsePage.records = records.map { model ->
            val response = ModelResponse.fromEntity(model)
            // 填充供应商名称
            model.providerId?.let { pid ->
                val provider = modelProviderMapper.selectById(pid)
                provider?.let {
                    response.providerName = it.displayName
                }
            }
            response
        }

        return responsePage
    }

    override fun getDetail(id: Long): ModelResponse {
        val model = this.modelMapper.selectById(id)
            ?: throw BizException("模型不存在")
        val response = ModelResponse.fromEntity(model)
        // 填充供应商名称
        model.providerId?.let { providerId ->
            val provider = modelProviderMapper.selectById(providerId)
            provider?.let {
                response.providerName = it.displayName
            }
        }
        return response
    }

    override fun create(request: ModelCreateRequest): ModelResponse {
        // 检查供应商是否存在
        val provider = modelProviderMapper.selectById(request.providerId!!)
            ?: throw BizException("模型供应商不存在")

        // 检查同一供应商下 name 是否已存在
        if (modelMapper.countByProviderIdAndName(request.providerId, request.name!!) > 0) {
            throw BizException("该模型名称在当前供应商下已存在")
        }

        // 检查同一供应商下 model_name 是否已存在
        if (modelMapper.countByProviderIdAndModelName(request.providerId, request.modelName!!) > 0) {
            throw BizException("该模型标识在当前供应商下已存在")
        }

        val model = Model()
        BeanUtils.copyProperties(request, model)

        // 设置创建人
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        model.creator = currentUsername
        this.modelMapper.insert(model) > 0

        val response = ModelResponse.fromEntity(model)
        response.providerName = provider.displayName
        return response
    }

    override fun update(id: Long, request: ModelUpdateRequest): ModelResponse {
        val model = modelMapper.selectActiveById(id)
            ?: throw BizException("模型不存在")

        // 如果修改了供应商，检查是否存在
        if (request.providerId != model.providerId) {
            val provider = modelProviderMapper.selectById(request.providerId)
                ?: throw BizException("模型供应商不存在")
        }

        // 如果修改了 name，检查是否与其他模型冲突
        if (hasText(request.name) && request.name != model.name) {
            if (modelMapper.countByProviderIdAndName(model.providerId!!, request.name!!) > 0) {
                throw BizException("该模型名称在当前供应商下已存在")
            }
            model.name = request.name
        } else if (hasText(request.name)) {
            model.name = request.name
        }

        // 如果修改了 modelName，检查是否与其他模型冲突
        if (hasText(request.modelName) && request.modelName != model.modelName) {
            if (modelMapper.countByProviderIdAndModelName(model.providerId!!, request.modelName!!) > 0) {
                throw BizException("该模型标识在当前供应商下已存在")
            }
            model.modelName = request.modelName
        } else if (hasText(request.modelName)) {
            model.modelName = request.modelName
        }

        request.providerId.let { model.providerId = it }
        request.description.let { model.description = it }
        if (hasText(request.modelType)) {
            model.modelType = request.modelType
        }
        request.supportInternet.let { model.supportInternet = it }
        request.supportReasoning.let { model.supportReasoning = it }
        request.supportTool.let { model.supportTool = it }
        request.supportMcp.let { model.supportMcp = it }
        request.supportVision.let { model.supportVision = it }
        request.price.let { model.price = it }
        request.status.let { model.status = it }

        this.modelMapper.updateById(model) > 0
        return getDetail(id)
    }

    override fun toggle(id: Long): ModelResponse {
        val model = this.modelMapper.selectById(id)
            ?: throw BizException("模型不存在")

        // 切换状态
        model.status = if (model.status == 1) 0 else 1
        this.modelMapper.updateById(model) > 0

        return getDetail(id)
    }

    override fun getModelById(id: Long): Model? {
        return this.modelMapper.selectById(id)
    }

    override fun deleteById(id: Long) {
        log.info("删除模型，id: {}", id)
        val model = modelMapper.selectActiveById(id)
            ?: throw BizException("模型不存在")

        // 逻辑删除：设置 active = 0
        modelMapper.deleteById(id)
        log.info("模型删除成功，id: {}", id)
    }
}
