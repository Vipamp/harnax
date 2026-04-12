package com.vipamp.vipclaw.agent.adaptor.model

import com.vipamp.vipclaw.agent.ChatSpec
import io.agentscope.core.model.ChatModelBase
import io.agentscope.core.model.DashScopeChatModel
import io.agentscope.core.model.OllamaChatModel
import io.agentscope.core.model.OpenAIChatModel
import io.agentscope.core.model.ollama.OllamaOptions
import io.agentscope.core.model.ollama.ThinkOption

/**
 * 模型辅助工具类
 * 提供基于 ModelConfig 创建 ChatModel 的方法
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Project: vipclaw
 */
object ModelHelper {

    /**
     * 根据模型配置创建 ChatModel 实例
     *
     * @param chatModelConfig 模型配置，支持 DashScope、OpenAI、Ollama
     * @return ChatModel 实例
     * @throws ModelErrorCode.MODEL_CREATE_FAILED 当模型创建失败时抛出
     */
    fun createChatModel(chatModelConfig: ChatModelConfig, chatSpec: ChatSpec): ChatModelBase {
        return try {
            when (chatModelConfig) {
                is DashScopeChatModelConfig -> buildDashScopeChatModel(chatModelConfig, chatSpec)
                is OpenAIChatModelConfig -> buildOpenAIChatModel(chatModelConfig, chatSpec)
                is OllamaChatModelConfig -> buildOllamaChatModel(chatModelConfig, chatSpec)
            }
        } catch (e: Exception) {
            throw ModelErrorCode.MODEL_CREATE_FAILED.format(e, chatModelConfig.modelName)
        }
    }

    /**
     * 构建 DashScope ChatModel
     *
     * @param config DashScope 模型配置
     * @return DashScopeChatModel 实例
     */
    private fun buildDashScopeChatModel(
        config: DashScopeChatModelConfig,
        chatSpec: ChatSpec
    ): ChatModelBase {
        val builder = DashScopeChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .stream(config.stream)
            .enableThinking(chatSpec.enableThinking ?: config.enableThinking)
            .enableSearch(chatSpec.enableSearch ?: config.enableSearch)
        config.baseUrl?.let { builder.baseUrl(it) }
        config.httpTransport?.let { builder.httpTransport(it) }
        config.options?.let { builder.defaultOptions(it) }
        return builder.build()
    }

    /**
     * 构建 OpenAI ChatModel
     *
     * @param config OpenAI 模型配置
     * @return OpenAIChatModel 实例
     */
    private fun buildOpenAIChatModel(
        config: OpenAIChatModelConfig,
        chatSpec: ChatSpec
    ): ChatModelBase {
        val builder = OpenAIChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .stream(config.stream)
        config.baseUrl?.let { builder.baseUrl(it) }
        config.endpointPath?.let { builder.endpointPath(it) }
        config.httpTransport?.let { builder.httpTransport(it) }
        config.options?.let { builder.generateOptions(it) }

        return builder.build()
    }

    /**
     * 构建 Ollama ChatModel
     *
     * @param config Ollama 模型配置
     * @return OllamaChatModel 实例
     */
    private fun buildOllamaChatModel(
        config: OllamaChatModelConfig,
        chatSpec: ChatSpec
    ): ChatModelBase {
        val options = config.options ?: OllamaOptions.builder().build()
        if (chatSpec.enableThinking != null) {
            options.thinkOption =
                if (chatSpec.enableThinking) ThinkOption.ThinkBoolean.ENABLED else ThinkOption.ThinkBoolean.DISABLED
        }
        val builder = OllamaChatModel.builder()
            .modelName(config.modelName)
            .baseUrl(config.baseUrl)
        config.httpTransport?.let { builder.httpTransport(it) }
        config.options?.let { builder.defaultOptions(it) }
        return builder.build()
    }
}
