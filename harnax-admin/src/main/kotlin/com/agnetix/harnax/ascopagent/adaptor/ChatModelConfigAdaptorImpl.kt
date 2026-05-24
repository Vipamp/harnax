package com.agnetix.harnax.ascopagent.adaptor

import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.model.ChatModelConfig
import com.agnetix.harnax.agent.adaptor.model.DashScopeChatModelConfig
import com.agnetix.harnax.agent.adaptor.model.OllamaChatModelConfig
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.entity.ModelProvider
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.ModelProviderMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * ChatModelConfigAdaptor Implementation
 * Loads model configuration from database and converts to ChatModelConfig
 */
@Component
class ChatModelConfigAdaptorImpl(
    private val modelMapper: ModelMapper,
    private val modelProviderMapper: ModelProviderMapper,
) : ChatModelConfigAdaptor {

    private val log = LoggerFactory.getLogger(ChatModelConfigAdaptorImpl::class.java)

    override fun getConfig(modelId: Long): ChatModelConfig? {
        if (modelId <= 0) {
            log.warn("Invalid modelId: $modelId")
            return null
        }

        // Query model information
        val model = modelMapper.selectById(modelId)
        if (model == null) {
            log.warn("Model not found: $modelId")
            return null
        }

        // Query model provider information
        val provider = modelProviderMapper.selectById(model.providerId)
        if (provider == null) {
            log.warn("Model provider not found: ${model.providerId}")
            return null
        }

        // Create corresponding configuration based on provider type
        return buildChatModelConfig(model, provider)
    }

    /**
     * Build ChatModelConfig based on provider type
     */
    private fun buildChatModelConfig(model: Model, provider: ModelProvider): ChatModelConfig? {
        val providerType = provider.name?.lowercase()

        return when (providerType) {
            "dashscope" -> DashScopeChatModelConfig(
                model.modelName,
                provider.apiKey!!,
                provider.baseUrl,
                stream = true,
                enableThinking = true,
                enableSearch = false,
                httpTransport = null,
                options = null,
                encrypt = false,
            )

            "openai" -> OpenAIChatModelConfig(
                model.modelName,
                provider.apiKey!!,
                provider.baseUrl,
                true,
                null,
                null,
                null,
            )

            "ollama" -> OllamaChatModelConfig(
                model.modelName,
                provider.baseUrl ?: "http://localhost:11434",
                null,
                null,
            )

            else -> {
                log.warn("Unsupported provider type: $providerType")
                null
            }
        }
    }
}
