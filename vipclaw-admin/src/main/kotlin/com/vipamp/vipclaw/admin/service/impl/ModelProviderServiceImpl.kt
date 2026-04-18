package com.vipamp.vipclaw.admin.service.impl

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
import com.baomidou.mybatisplus.extension.plugins.pagination.Page
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import com.vipamp.vipclaw.admin.dto.ModelProviderCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelProviderResponse
import com.vipamp.vipclaw.admin.dto.ModelProviderUpdateRequest
import com.vipamp.vipclaw.admin.entity.ModelProvider
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.ModelMapper
import com.vipamp.vipclaw.admin.mapper.ModelProviderMapper
import com.vipamp.vipclaw.admin.service.ModelProviderService
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.admin.util.UserContextUtil
import com.vipamp.vipclaw.common.entity.Model
import org.slf4j.LoggerFactory
import org.springframework.beans.BeanUtils
import org.springframework.stereotype.Service
import org.springframework.util.StringUtils.hasText

/**
 * 模型服务商服务实现类
 *
 * @author vipamp
 * @since 2026-03-13
 */
@Service
class ModelProviderServiceImpl(
    private val modelMapper: ModelMapper,
    private val jwtUtil: JwtUtil
) : ServiceImpl<ModelProviderMapper, ModelProvider>(), ModelProviderService {

    private val log = LoggerFactory.getLogger(ModelProviderServiceImpl::class.java)

    override fun page(page: Page<ModelProvider>, name: String?, status: Int?): Page<ModelProviderResponse> {
        val queryWrapper = LambdaQueryWrapper<ModelProvider>()

        // 获取当前用户
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)

        // 权限过滤：只查询公开的或自己创建的
        queryWrapper.and { wrapper ->
            wrapper.eq(ModelProvider::isPublic, 1)
                .or()
                .eq(ModelProvider::creator, currentUsername)
        }

        // 按名称模糊查询
        if (hasText(name)) {
            queryWrapper.and { wrapper ->
                wrapper.like(ModelProvider::name, name)
                    .or()
                    .like(ModelProvider::displayName, name)
            }
        }

        // 按状态筛选
        status?.let { queryWrapper.eq(ModelProvider::status, it) }

        // 按状态升序排序（启用在前面，禁用在后面），再按更新时间倒序排序
        queryWrapper.orderByDesc(ModelProvider::status)
            .orderByDesc(ModelProvider::updateTime)

        val result = this.page(page, queryWrapper)

        // 转换为响应对象
        val responsePage = Page<ModelProviderResponse>(result.current, result.size, result.total)
        responsePage.records = result.records.map { ModelProviderResponse.fromEntity(it) }

        return responsePage
    }

    override fun getDetail(id: Long): ModelProviderResponse {
        val modelProvider = this.getById(id)
            ?: throw BizException("模型服务商不存在")
        return ModelProviderResponse.fromEntity(modelProvider)
    }

    override fun create(request: ModelProviderCreateRequest): ModelProviderResponse {
        // 检查名称是否已存在
        val queryWrapper = LambdaQueryWrapper<ModelProvider>()
        queryWrapper.eq(ModelProvider::name, request.name)
        if (this.count(queryWrapper) > 0) {
            throw BizException("服务商名称已存在")
        }

        val modelProvider = ModelProvider()
        BeanUtils.copyProperties(request, modelProvider)

        // 默认状态为启用
        if (modelProvider.status == null) {
            modelProvider.status = 1
        }

        // 设置创建人
        val currentUsername = UserContextUtil.getCurrentUsername(jwtUtil)
        modelProvider.creator = currentUsername

        // 默认不公开
        if (modelProvider.isPublic == null) {
            modelProvider.isPublic = 0
        }

        this.save(modelProvider)
        return ModelProviderResponse.fromEntity(modelProvider)
    }

    override fun update(id: Long, request: ModelProviderUpdateRequest): ModelProviderResponse {
        val modelProvider = this.getById(id)
            ?: throw BizException("模型服务商不存在")

        // 如果修改了名称，检查是否重复
        if (hasText(request.name) && request.name != modelProvider.name) {
            val queryWrapper = LambdaQueryWrapper<ModelProvider>()
            queryWrapper.eq(ModelProvider::name, request.name)
            if (this.count(queryWrapper) > 0) {
                throw BizException("服务商名称已存在")
            }
            modelProvider.name = request.name
        }

        // 更新其他字段
        if (hasText(request.displayName)) {
            modelProvider.displayName = request.displayName
        }
        request.apiKey?.let { apiKey ->
            // 如果 API Key 不为空且不是脱敏格式，则更新
            if (!apiKey.contains("****")) {
                modelProvider.apiKey = apiKey
            }
        }
        request.baseUrl?.let { modelProvider.baseUrl = it }
        request.status?.let { modelProvider.status = it }

        this.updateById(modelProvider)
        return ModelProviderResponse.fromEntity(modelProvider)
    }

    override fun toggle(id: Long): ModelProviderResponse {
        val modelProvider = this.getById(id)
            ?: throw BizException("模型服务商不存在")

        // 如果要禁用，检查是否有启用的模型
        if (modelProvider.status == 1) {
            val modelQuery = LambdaQueryWrapper<Model>()
            modelQuery.eq(Model::getProviderId, id)
                .eq(Model::getStatus, 1)
                .eq(Model::getActive, 1)
            if (modelMapper.selectCount(modelQuery) > 0) {
                throw BizException("该服务商下有启用的模型，无法禁用")
            }
        }

        // 切换状态
        modelProvider.status = if (modelProvider.status == 1) 0 else 1
        this.updateById(modelProvider)

        return ModelProviderResponse.fromEntity(modelProvider)
    }

    override fun removeProviderById(id: Long): Boolean {
        // 检查是否有启用的模型
        val modelQuery = LambdaQueryWrapper<Model>()
        modelQuery.eq(Model::getProviderId, id)
            .eq(Model::getStatus, 1)
            .eq(Model::getActive, 1)
        if (modelMapper.selectCount(modelQuery) > 0) {
            throw BizException("该服务商下有启用的模型，无法删除")
        }
        return super.removeById(id)
    }

    override fun connectivityTest(id: Long): Boolean {
        val modelProvider = this.getById(id)
            ?: throw BizException("模型服务商不存在")

        // TODO: 实现实际的连接测试逻辑
        // 目前直接返回 true
        return true
    }
}
