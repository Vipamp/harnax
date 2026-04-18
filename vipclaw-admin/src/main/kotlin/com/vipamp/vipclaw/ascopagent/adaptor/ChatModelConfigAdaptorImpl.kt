package com.vipamp.vipclaw.ascopagent.adaptor

import com.vipamp.vipclaw.admin.entity.Model
import com.vipamp.vipclaw.admin.entity.ModelProvider
import com.vipamp.vipclaw.admin.mapper.ModelMapper
import com.vipamp.vipclaw.admin.mapper.ModelProviderMapper
import com.vipamp.vipclaw.agent.adaptor.ChatModelConfigAdaptor
import com.vipamp.vipclaw.agent.adaptor.model.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * ChatModelConfigAdaptor 实现类
 * 从数据库加载模型配置并转换为 ChatModelConfig
 */
@Component
class ChatModelConfigAdaptorImpl(
    private val modelMapper: ModelMapper,
    private val modelProviderMapper: ModelProviderMapper
) : ChatModelConfigAdaptor {

    private val log = LoggerFactory.getLogger(ChatModelConfigAdaptorImpl::class.java)

    override fun getConfig(modelId: Long): ChatModelConfig? {
        if (modelId <= 0) {
            log.warn("Invalid modelId: $modelId")
            return null
        }

        // 查询模型信息
        val model = modelMapper.selectById(modelId)
        if (model == null) {
            log.warn("Model not found: $modelId")
            return null
        }

        // 查询模型服务商信息
        val provider = modelProviderMapper.selectById(model.providerId)
        if (provider == null) {
            log.warn("Model provider not found: ${model.providerId}")
            return null
        }

        // 根据服务商类型创建对应的配置
        return buildChatModelConfig(model, provider)
    }

    /**
     * 根据服务商类型构建 ChatModelConfig
     */
    private fun buildChatModelConfig(model: Model, provider: ModelProvider): ChatModelConfig? {
        val providerType = provider.name?.lowercase()

        return when (providerType) {
            "dashscope" -> DashScopeChatModelConfig(
                model.modelName,
                provider.apiKey,
                provider.baseUrl,
                true,
                true,
                false,
                null,
                null,
                false
            )
            "openai" -> OpenAIChatModelConfig(
                model.modelName,
                provider.apiKey,
                provider.baseUrl,
                true,
                null,
                null,
                null
            )
            "ollama" -> OllamaChatModelConfig(
                model.modelName,
                provider.baseUrl ?: "http://localhost:11434",
                null,
                null
            )
            else -> {
                log.warn("Unsupported provider type: $providerType")
                null
            }
        }
    }
}
