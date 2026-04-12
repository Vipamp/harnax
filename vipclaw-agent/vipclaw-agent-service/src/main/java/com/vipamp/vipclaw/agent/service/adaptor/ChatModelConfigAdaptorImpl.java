package com.vipamp.vipclaw.agent.service.adaptor;

import com.vipamp.vipclaw.agent.adaptor.ChatModelConfigAdaptor;
import com.vipamp.vipclaw.agent.adaptor.model.ChatModelConfig;
import com.vipamp.vipclaw.agent.adaptor.model.DashScopeChatModelConfig;
import com.vipamp.vipclaw.agent.adaptor.model.OllamaChatModelConfig;
import com.vipamp.vipclaw.agent.adaptor.model.OpenAIChatModelConfig;
import com.vipamp.vipclaw.common.entity.Model;
import com.vipamp.vipclaw.common.entity.ModelProvider;
import com.vipamp.vipclaw.common.mapper.ModelMapper;
import com.vipamp.vipclaw.common.mapper.ModelProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ChatModelConfigAdaptor 实现类
 * 从数据库加载模型配置并转换为 ChatModelConfig
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelConfigAdaptorImpl implements ChatModelConfigAdaptor {

    private final ModelMapper modelMapper;
    private final ModelProviderMapper modelProviderMapper;

    @Override
    public ChatModelConfig getConfig(long modelId) {
        if (modelId <= 0) {
            log.warn("Invalid modelId: {}", modelId);
            return null;
        }

        // 查询模型信息
        Model model = modelMapper.selectById(modelId);
        if (model == null) {
            log.warn("Model not found: {}", modelId);
            return null;
        }

        // 查询模型服务商信息
        ModelProvider provider = modelProviderMapper.selectById(model.getProviderId());
        if (provider == null) {
            log.warn("Model provider not found: {}", model.getProviderId());
            return null;
        }

        // 根据服务商类型创建对应的配置
        return buildChatModelConfig(model, provider);
    }

    /**
     * 根据服务商类型构建 ChatModelConfig
     */
    private ChatModelConfig buildChatModelConfig(Model model, ModelProvider provider) {
        String providerType = provider.getName().toLowerCase();

        return switch (providerType) {
            case "dashscope" -> new DashScopeChatModelConfig(
                    model.getModelName(),
                    provider.getApiKey(),
                    provider.getBaseUrl(),
                    true,
                    true,
                    false,
                    null,
                    null,
                    false
            );
            case "openai" -> new OpenAIChatModelConfig(
                    model.getModelName(),
                    provider.getApiKey(),
                    provider.getBaseUrl(),
                    true,
                    null,
                    null,
                    null
            );
            case "ollama" -> new OllamaChatModelConfig(
                    model.getModelName(),
                    provider.getBaseUrl() != null ? provider.getBaseUrl() : "http://localhost:11434",
                    null,
                    null
            );
            default -> {
                log.warn("Unsupported provider type: {}", providerType);
                yield null;
            }
        };
    }
}
