package com.vipclaw.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vipclaw.admin.dto.ModelProviderCreateRequest;
import com.vipclaw.admin.dto.ModelProviderResponse;
import com.vipclaw.admin.dto.ModelProviderUpdateRequest;
import com.vipclaw.admin.entity.ModelProvider;
import com.vipclaw.admin.entity.Model;
import com.vipclaw.admin.exception.BizException;
import com.vipclaw.admin.mapper.ModelMapper;
import com.vipclaw.admin.mapper.ModelProviderMapper;
import com.vipclaw.admin.service.ModelProviderService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 模型服务商服务实现类
 *
 * @author vipamp
 * @since 2026-03-13
 */
@Service
public class ModelProviderServiceImpl extends ServiceImpl<ModelProviderMapper, ModelProvider> implements ModelProviderService {

    @Autowired
    private ModelMapper modelMapper;

    @Override
    public Page<ModelProviderResponse> page(Page<ModelProvider> page, String name, Integer status) {
        LambdaQueryWrapper<ModelProvider> queryWrapper = new LambdaQueryWrapper<>();
        
        // 按名称模糊查询
        if (StringUtils.hasText(name)) {
            queryWrapper.like(ModelProvider::getName, name)
                    .or()
                    .like(ModelProvider::getDisplayName, name);
        }
        
        // 按状态筛选
        if (status != null) {
            queryWrapper.eq(ModelProvider::getStatus, status);
        }
        
        // 按状态升序排序（启用在前面，禁用在后面），再按更新时间倒序排序
        queryWrapper.orderByDesc(ModelProvider::getStatus)
                .orderByDesc(ModelProvider::getUpdateTime);
        
        Page<ModelProvider> result = this.page(page, queryWrapper);
        
        // 转换为响应对象
        Page<ModelProviderResponse> responsePage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        responsePage.setRecords(result.getRecords().stream()
                .map(ModelProviderResponse::fromEntity)
                .toList());
        
        return responsePage;
    }

    @Override
    public ModelProviderResponse getDetail(Long id) {
        ModelProvider modelProvider = this.getById(id);
        if (modelProvider == null) {
            throw new BizException("模型服务商不存在");
        }
        return ModelProviderResponse.fromEntity(modelProvider);
    }

    @Override
    public ModelProviderResponse create(ModelProviderCreateRequest request) {
        // 检查名称是否已存在
        LambdaQueryWrapper<ModelProvider> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ModelProvider::getName, request.getName());
        if (this.count(queryWrapper) > 0) {
            throw new BizException("服务商名称已存在");
        }
        
        ModelProvider modelProvider = new ModelProvider();
        BeanUtils.copyProperties(request, modelProvider);
        
        // 默认状态为启用
        if (modelProvider.getStatus() == null) {
            modelProvider.setStatus(1);
        }
        
        this.save(modelProvider);
        return ModelProviderResponse.fromEntity(modelProvider);
    }

    @Override
    public ModelProviderResponse update(Long id, ModelProviderUpdateRequest request) {
        ModelProvider modelProvider = this.getById(id);
        if (modelProvider == null) {
            throw new BizException("模型服务商不存在");
        }
        
        // 如果修改了名称，检查是否重复
        if (StringUtils.hasText(request.getName()) && !request.getName().equals(modelProvider.getName())) {
            LambdaQueryWrapper<ModelProvider> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(ModelProvider::getName, request.getName());
            if (this.count(queryWrapper) > 0) {
                throw new BizException("服务商名称已存在");
            }
            modelProvider.setName(request.getName());
        }
        
        // 更新其他字段
        if (StringUtils.hasText(request.getDisplayName())) {
            modelProvider.setDisplayName(request.getDisplayName());
        }
        if (request.getApiKey() != null) {
            // 如果 API Key 不为空且不是脱敏格式，则更新
            if (!request.getApiKey().contains("****")) {
                modelProvider.setApiKey(request.getApiKey());
            }
        }
        if (request.getBaseUrl() != null) {
            modelProvider.setBaseUrl(request.getBaseUrl());
        }
        if (request.getStatus() != null) {
            modelProvider.setStatus(request.getStatus());
        }
        
        this.updateById(modelProvider);
        return ModelProviderResponse.fromEntity(modelProvider);
    }

    @Override
    public ModelProviderResponse toggle(Long id) {
        ModelProvider modelProvider = this.getById(id);
        if (modelProvider == null) {
            throw new BizException("模型服务商不存在");
        }
        
        // 如果要禁用，检查是否有启用的模型
        if (modelProvider.getStatus() == 1) {
            LambdaQueryWrapper<Model> modelQuery = new LambdaQueryWrapper<>();
            modelQuery.eq(Model::getProviderId, id)
                    .eq(Model::getStatus, 1)
                    .eq(Model::getActive, 1);
            if (modelMapper.selectCount(modelQuery) > 0) {
                throw new BizException("该服务商下有启用的模型，无法禁用");
            }
        }
        
        // 切换状态
        modelProvider.setStatus(modelProvider.getStatus() == 1 ? 0 : 1);
        this.updateById(modelProvider);
        
        return ModelProviderResponse.fromEntity(modelProvider);
    }

    public boolean removeProviderById(Long id) {
        // 检查是否有启用的模型
        LambdaQueryWrapper<Model> modelQuery = new LambdaQueryWrapper<>();
        modelQuery.eq(Model::getProviderId, id)
                .eq(Model::getStatus, 1)
                .eq(Model::getActive, 1);
        if (modelMapper.selectCount(modelQuery) > 0) {
            throw new BizException("该服务商下有启用的模型，无法删除");
        }
        return super.removeById(id);
    }

    @Override
    public boolean connectivityTest(Long id) {
        ModelProvider modelProvider = this.getById(id);
        if (modelProvider == null) {
            throw new BizException("模型服务商不存在");
        }
        
        // TODO: 实现实际的连接测试逻辑
        // 目前直接返回 true
        return true;
    }
}
