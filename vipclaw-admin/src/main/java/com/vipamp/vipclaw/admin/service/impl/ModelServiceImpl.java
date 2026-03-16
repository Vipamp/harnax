package com.vipamp.vipclaw.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.vipamp.vipclaw.admin.dto.ModelCreateRequest;
import com.vipamp.vipclaw.admin.dto.ModelResponse;
import com.vipamp.vipclaw.admin.dto.ModelUpdateRequest;
import com.vipamp.vipclaw.admin.entity.Model;
import com.vipamp.vipclaw.admin.entity.ModelProvider;
import com.vipamp.vipclaw.admin.exception.BizException;
import com.vipamp.vipclaw.admin.mapper.ModelMapper;
import com.vipamp.vipclaw.admin.mapper.ModelProviderMapper;
import com.vipamp.vipclaw.admin.service.ModelService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 模型服务实现类
 *
 * @author vipamp
 * @since 2026-03-13
 */
@Service
public class ModelServiceImpl extends ServiceImpl<ModelMapper, Model> implements ModelService {

    @Autowired
    private ModelProviderMapper modelProviderMapper;

    @Override
    public Page<ModelResponse> page(Page<Model> page, String name, Long providerId, String modelType, Integer status, String tags, Double minPrice, Double maxPrice) {
        LambdaQueryWrapper<Model> queryWrapper = new LambdaQueryWrapper<>();

        // 按名称模糊查询
        if (StringUtils.hasText(name)) {
            queryWrapper.and(wrapper -> wrapper
                    .like(Model::getName, name)
                    .or()
                    .like(Model::getModelName, name));
        }

        // 按供应商筛选
        if (providerId != null) {
            queryWrapper.eq(Model::getProviderId, providerId);
        }

        // 按模型类型筛选
        if (StringUtils.hasText(modelType)) {
            queryWrapper.eq(Model::getModelType, modelType);
        }

        // 按状态筛选
        if (status != null) {
            queryWrapper.eq(Model::getStatus, status);
        }

        // 按标签筛选（支持多个标签，如：internet,reasoning,tool,mcp,vision）
        // 多个标签之间是"或"关系，只要满足其中一个即可
        if (StringUtils.hasText(tags)) {
            String[] tagArray = tags.split(",");
            queryWrapper.and(wrapper -> {
                for (String tag : tagArray) {
                    String trimmedTag = tag.trim();
                    if ("internet".equalsIgnoreCase(trimmedTag)) {
                        wrapper.or().eq(Model::getSupportInternet, 1);
                    } else if ("reasoning".equalsIgnoreCase(trimmedTag)) {
                        wrapper.or().eq(Model::getSupportReasoning, 1);
                    } else if ("tool".equalsIgnoreCase(trimmedTag)) {
                        wrapper.or().eq(Model::getSupportTool, 1);
                    } else if ("mcp".equalsIgnoreCase(trimmedTag)) {
                        wrapper.or().eq(Model::getSupportMcp, 1);
                    } else if ("vision".equalsIgnoreCase(trimmedTag)) {
                        wrapper.or().eq(Model::getSupportVision, 1);
                    }
                }
            });
        }

        // 按价格范围筛选
        if (minPrice != null) {
            queryWrapper.ge(Model::getPrice, minPrice);
        }
        if (maxPrice != null) {
            queryWrapper.le(Model::getPrice, maxPrice);
        }

        // 按更新时间倒序排序
        queryWrapper.orderByDesc(Model::getStatus)
                .orderByDesc(Model::getUpdateTime);

        Page<Model> result = this.page(page, queryWrapper);

        // 转换为响应对象
        Page<ModelResponse> responsePage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        responsePage.setRecords(result.getRecords().stream()
                .map(model -> {
                    ModelResponse response = ModelResponse.fromEntity(model);
                    // 填充供应商名称
                    if (model.getProviderId() != null) {
                        ModelProvider provider = modelProviderMapper.selectById(model.getProviderId());
                        if (provider != null) {
                            response.setProviderName(provider.getDisplayName());
                        }
                    }
                    return response;
                })
                .toList());

        return responsePage;
    }

    @Override
    public ModelResponse getDetail(Long id) {
        Model model = this.getById(id);
        if (model == null) {
            throw new BizException("模型不存在");
        }
        ModelResponse response = ModelResponse.fromEntity(model);
        // 填充供应商名称
        if (model.getProviderId() != null) {
            ModelProvider provider = modelProviderMapper.selectById(model.getProviderId());
            if (provider != null) {
                response.setProviderName(provider.getDisplayName());
            }
        }
        return response;
    }

    @Override
    public ModelResponse create(ModelCreateRequest request) {
        // 检查供应商是否存在
        ModelProvider provider = modelProviderMapper.selectById(request.getProviderId());
        if (provider == null) {
            throw new BizException("模型供应商不存在");
        }

        // 检查同一供应商下 name 是否已存在
        LambdaQueryWrapper<Model> nameQuery = new LambdaQueryWrapper<>();
        nameQuery.eq(Model::getProviderId, request.getProviderId())
                .eq(Model::getName, request.getName())
                .eq(Model::getActive, 1);
        if (this.count(nameQuery) > 0) {
            throw new BizException("该模型名称在当前供应商下已存在");
        }

        // 检查同一供应商下 model_name 是否已存在
        LambdaQueryWrapper<Model> modelNameQuery = new LambdaQueryWrapper<>();
        modelNameQuery.eq(Model::getProviderId, request.getProviderId())
                .eq(Model::getModelName, request.getModelName())
                .eq(Model::getActive, 1);
        if (this.count(modelNameQuery) > 0) {
            throw new BizException("该模型标识在当前供应商下已存在");
        }

        Model model = new Model();
        BeanUtils.copyProperties(request, model);

        // 默认状态为启用
        if (model.getStatus() == null) {
            model.setStatus(1);
        }

        // 默认不支持各项能力
        if (model.getSupportInternet() == null) {
            model.setSupportInternet(0);
        }
        if (model.getSupportReasoning() == null) {
            model.setSupportReasoning(0);
        }
        if (model.getSupportTool() == null) {
            model.setSupportTool(0);
        }
        if (model.getSupportMcp() == null) {
            model.setSupportMcp(0);
        }
        if (model.getSupportVision() == null) {
            model.setSupportVision(0);
        }

        this.save(model);

        ModelResponse response = ModelResponse.fromEntity(model);
        response.setProviderName(provider.getDisplayName());
        return response;
    }

    @Override
    public ModelResponse update(Long id, ModelUpdateRequest request) {
        Model model = this.getById(id);
        if (model == null) {
            throw new BizException("模型不存在");
        }

        // 如果修改了供应商，检查是否存在
        if (request.getProviderId() != null && !request.getProviderId().equals(model.getProviderId())) {
            ModelProvider provider = modelProviderMapper.selectById(request.getProviderId());
            if (provider == null) {
                throw new BizException("模型供应商不存在");
            }
        }

        // 如果修改了 name，检查是否与其他模型冲突
        if (StringUtils.hasText(request.getName()) && !request.getName().equals(model.getName())) {
            LambdaQueryWrapper<Model> nameQuery = new LambdaQueryWrapper<>();
            nameQuery.eq(Model::getProviderId, model.getProviderId())
                    .eq(Model::getName, request.getName())
                    .eq(Model::getActive, 1)
                    .ne(Model::getId, id);
            if (this.count(nameQuery) > 0) {
                throw new BizException("该模型名称在当前供应商下已存在");
            }
            model.setName(request.getName());
        } else if (StringUtils.hasText(request.getName())) {
            model.setName(request.getName());
        }

        // 如果修改了 modelName，检查是否与其他模型冲突
        if (StringUtils.hasText(request.getModelName()) && !request.getModelName().equals(model.getModelName())) {
            LambdaQueryWrapper<Model> modelNameQuery = new LambdaQueryWrapper<>();
            modelNameQuery.eq(Model::getProviderId, model.getProviderId())
                    .eq(Model::getModelName, request.getModelName())
                    .eq(Model::getActive, 1)
                    .ne(Model::getId, id);
            if (this.count(modelNameQuery) > 0) {
                throw new BizException("该模型标识在当前供应商下已存在");
            }
            model.setModelName(request.getModelName());
        } else if (StringUtils.hasText(request.getModelName())) {
            model.setModelName(request.getModelName());
        }
        if (request.getProviderId() != null) {
            model.setProviderId(request.getProviderId());
        }
        if (request.getDescription() != null) {
            model.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getModelType())) {
            model.setModelType(request.getModelType());
        }
        if (request.getSupportInternet() != null) {
            model.setSupportInternet(request.getSupportInternet());
        }
        if (request.getSupportReasoning() != null) {
            model.setSupportReasoning(request.getSupportReasoning());
        }
        if (request.getSupportTool() != null) {
            model.setSupportTool(request.getSupportTool());
        }
        if (request.getSupportMcp() != null) {
            model.setSupportMcp(request.getSupportMcp());
        }
        if (request.getSupportVision() != null) {
            model.setSupportVision(request.getSupportVision());
        }
        if (request.getPrice() != null) {
            model.setPrice(request.getPrice());
        }
        if (request.getStatus() != null) {
            model.setStatus(request.getStatus());
        }

        this.updateById(model);
        return getDetail(id);
    }

    @Override
    public ModelResponse toggle(Long id) {
        Model model = this.getById(id);
        if (model == null) {
            throw new BizException("模型不存在");
        }

        // 切换状态
        model.setStatus(model.getStatus() == 1 ? 0 : 1);
        this.updateById(model);

        return getDetail(id);
    }
}
