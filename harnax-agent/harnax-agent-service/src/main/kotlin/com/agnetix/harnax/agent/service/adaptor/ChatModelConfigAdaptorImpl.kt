package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.model.ChatModelConfig
import com.agnetix.harnax.agent.adaptor.model.DashScopeChatModelConfig
import com.agnetix.harnax.agent.adaptor.model.OllamaChatModelConfig
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.entity.ModelProvider
import com.agnetix.harnax.entity.dto.ModelConfigDto
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.ModelProviderMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * ChatModelConfigAdaptor Implementation.
 *
 * **Primary path**: reads model config from [AgentSpecContextHolder] (populated by admin API).
 * **Fallback**: queries DB directly via [ModelMapper] + [ModelProviderMapper].
 */
@Component
class ChatModelConfigAdaptorImpl(
    private val specContextHolder: AgentSpecContextHolder,
    private val modelMapper: ModelMapper,
    private val modelProviderMapper: ModelProviderMapper,
) : ChatModelConfigAdaptor {

    private val log = LoggerFactory.getLogger(ChatModelConfigAdaptorImpl::class.java)

    override fun getConfig(modelId: Long): ChatModelConfig? {
        if (modelId <= 0) {
            log.warn("Invalid modelId: $modelId")
            return null
        }

        // Primary: read from context (admin pre-resolved)
        val modelConfig = specContextHolder.get()?.modelConfig
        if (modelConfig != null && modelConfig.modelId == modelId) {
            log.debug("Model config loaded from context: modelId={}, providerType={}", modelId, modelConfig.providerType)
            return buildFromDto(modelConfig)
        }

        // Fallback: direct DB query
        log.debug("Model config fallback to DB: modelId={}", modelId)
        val model = modelMapper.selectById(modelId)
        if (model == null) {
            log.warn("Model not found: $modelId")
            return null
        }
        val provider = modelProviderMapper.selectById(model.providerId)
        if (provider == null) {
            log.warn("Model provider not found: ${model.providerId}")
            return null
        }
        return buildFromEntities(model, provider)
    }

    private fun buildFromDto(cfg: ModelConfigDto): ChatModelConfig? = when (val providerType = cfg.providerType.lowercase()) {
        "dashscope" -> DashScopeChatModelConfig(
            cfg.modelName,
            cfg.apiKey!!,
            cfg.baseUrl,
            stream = true,
            enableThinking = true,
            enableSearch = false,
            httpTransport = null,
            options = null,
            encrypt = false,
        )
        "openai" -> OpenAIChatModelConfig(
            cfg.modelName,
            cfg.apiKey!!,
            cfg.baseUrl,
            true,
            null,
            null,
            null,
        )
        "ollama" -> OllamaChatModelConfig(
            cfg.modelName,
            cfg.baseUrl ?: "http://localhost:11434",
            null,
            null,
        )
        else -> {
            log.warn("Unsupported provider type: $providerType")
            null
        }
    }

    private fun buildFromEntities(model: Model, provider: ModelProvider): ChatModelConfig? = when (val providerType = provider.type.lowercase()) {
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
