package com.vipamp.vipclaw.admin.service.impl

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
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
import org.slf4j.LoggerFactory
import org.springframework.beans.BeanUtils
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils.hasText

/**
 * 模型服务实现类
 *
 * @author vipamp
 * @since 2026-03-13
 */
@Service
class ModelServiceImpl(
    private val modelProviderMapper: ModelProviderMapper,
    private val jwtUtil: JwtUtil
) : ServiceImpl<ModelMapper, Model>(), ModelService {

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
        val queryWrapper = LambdaQueryWrapper<Model>()

        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // 权限过滤：只查询公开的或自己创建的
        queryWrapper.and { wrapper ->
            wrapper.eq(Model::isPublic, 1)
                .or()
                .eq(Model::creator, currentUsername)
        }

        // 按名称模糊查询
        if (hasText(name)) {
            queryWrapper.and { wrapper ->
                wrapper.like(Model::name, name)
                    .or()
                    .like(Model::modelName, name)
            }
        }

        // 按供应商筛选
        providerId?.let { queryWrapper.eq(Model::providerId, it) }

        // 按模型类型筛选
        if (hasText(modelType)) {
            queryWrapper.eq(Model::modelType, modelType)
        }

        // 按状态筛选
        status?.let { queryWrapper.eq(Model::status, it) }

        // 按标签筛选（支持多个标签，如：internet,reasoning,tool,mcp,vision）
        // 多个标签之间是"或"关系，只要满足其中一个即可
        if (hasText(tags)) {
            val tagArray = tags!!.split(",")
            queryWrapper.and { wrapper ->
                tagArray.forEach { tag ->
                    val trimmedTag = tag.trim()
                    when {
                        "internet".equals(trimmedTag, ignoreCase = true) ->
                            wrapper.or().eq(Model::supportInternet, 1)

                        "reasoning".equals(trimmedTag, ignoreCase = true) ->
                            wrapper.or().eq(Model::supportReasoning, 1)

                        "tool".equals(trimmedTag, ignoreCase = true) ->
                            wrapper.or().eq(Model::supportTool, 1)

                        "mcp".equals(trimmedTag, ignoreCase = true) ->
                            wrapper.or().eq(Model::supportMcp, 1)

                        "vision".equals(trimmedTag, ignoreCase = true) ->
                            wrapper.or().eq(Model::supportVision, 1)
                    }
                }
            }
        }

        // 按价格范围筛选
        minPrice?.let { queryWrapper.ge(Model::price, it) }
        maxPrice?.let { queryWrapper.le(Model::price, it) }

        // 按更新时间倒序排序
        queryWrapper.orderByDesc(Model::status)
            .orderByDesc(Model::updateTime)

        val result = this.page(page, queryWrapper)

        // 转换为响应对象
        val responsePage = Page<ModelResponse>(result.current, result.size, result.total)
        responsePage.records = result.records.map { model ->
            val response = ModelResponse.fromEntity(model)
            // 填充供应商名称
            model.providerId?.let { providerId ->
                val provider = modelProviderMapper.selectById(providerId)
                provider?.let {
                    response.providerName = it.displayName
                }
            }
            response
        }

        return responsePage
    }

    override fun getDetail(id: Long): ModelResponse {
        val model = this.getById(id)
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
        val provider = modelProviderMapper.selectById(request.providerId)
            ?: throw BizException("模型供应商不存在")

        // 检查同一供应商下 name 是否已存在
        val nameQuery = LambdaQueryWrapper<Model>()
        nameQuery.eq(Model::providerId, request.providerId)
            .eq(Model::name, request.name)
            .eq(Model::active, 1)
        if (this.count(nameQuery) > 0) {
            throw BizException("该模型名称在当前供应商下已存在")
        }

        // 检查同一供应商下 model_name 是否已存在
        val modelNameQuery = LambdaQueryWrapper<Model>()
        modelNameQuery.eq(Model::providerId, request.providerId)
            .eq(Model::modelName, request.modelName)
            .eq(Model::active, 1)
        if (this.count(modelNameQuery) > 0) {
            throw BizException("该模型标识在当前供应商下已存在")
        }

        val model = Model()
        BeanUtils.copyProperties(request, model)

        // 默认状态为启用
        if (model.status == null) {
            model.status = 1
        }

        // 默认不支持各项能力
        if (model.supportInternet == null) model.supportInternet = 0
        if (model.supportReasoning == null) model.supportReasoning = 0
        if (model.supportTool == null) model.supportTool = 0
        if (model.supportMcp == null) model.supportMcp = 0
        if (model.supportVision == null) model.supportVision = 0

        // 设置创建人
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        model.creator = currentUsername

        // 默认不公开
        if (model.isPublic == null) {
            model.isPublic = 0
        }

        this.save(model)

        val response = ModelResponse.fromEntity(model)
        response.providerName = provider.displayName
        return response
    }

    override fun update(id: Long, request: ModelUpdateRequest): ModelResponse {
        val model = this.getById(id)
            ?: throw BizException("模型不存在")

        // 如果修改了供应商，检查是否存在
        if (request.providerId != null && request.providerId != model.providerId) {
            val provider = modelProviderMapper.selectById(request.providerId)
                ?: throw BizException("模型供应商不存在")
        }

        // 如果修改了 name，检查是否与其他模型冲突
        if (hasText(request.name) && request.name != model.name) {
            val nameQuery = LambdaQueryWrapper<Model>()
            nameQuery.eq(Model::providerId, model.providerId)
                .eq(Model::name, request.name)
                .eq(Model::active, 1)
                .ne(Model::id, id)
            if (this.count(nameQuery) > 0) {
                throw BizException("该模型名称在当前供应商下已存在")
            }
            model.name = request.name
        } else if (hasText(request.name)) {
            model.name = request.name
        }

        // 如果修改了 modelName，检查是否与其他模型冲突
        if (hasText(request.modelName) && request.modelName != model.modelName) {
            val modelNameQuery = LambdaQueryWrapper<Model>()
            modelNameQuery.eq(Model::providerId, model.providerId)
                .eq(Model::modelName, request.modelName)
                .eq(Model::active, 1)
                .ne(Model::id, id)
            if (this.count(modelNameQuery) > 0) {
                throw BizException("该模型标识在当前供应商下已存在")
            }
            model.modelName = request.modelName
        } else if (hasText(request.modelName)) {
            model.modelName = request.modelName
        }

        request.providerId?.let { model.providerId = it }
        request.description?.let { model.description = it }
        if (hasText(request.modelType)) {
            model.modelType = request.modelType
        }
        request.supportInternet?.let { model.supportInternet = it }
        request.supportReasoning?.let { model.supportReasoning = it }
        request.supportTool?.let { model.supportTool = it }
        request.supportMcp?.let { model.supportMcp = it }
        request.supportVision?.let { model.supportVision = it }
        request.price?.let { model.price = it }
        request.status?.let { model.status = it }

        this.updateById(model)
        return getDetail(id)
    }

    override fun toggle(id: Long): ModelResponse {
        val model = this.getById(id)
            ?: throw BizException("模型不存在")

        // 切换状态
        model.status = if (model.status == 1) 0 else 1
        this.updateById(model)

        return getDetail(id)
    }
}
