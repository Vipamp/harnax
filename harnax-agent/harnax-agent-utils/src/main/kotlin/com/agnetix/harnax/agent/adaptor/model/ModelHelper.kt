package com.agnetix.harnax.agent.adaptor.model

import io.agentscope.core.model.ChatModelBase
import io.agentscope.extensions.model.dashscope.DashScopeChatModel
import io.agentscope.extensions.model.dashscope.EndpointType
import io.agentscope.extensions.model.ollama.OllamaChatModel
import io.agentscope.extensions.model.ollama.options.OllamaOptions
import io.agentscope.extensions.model.ollama.options.ThinkOption
import io.agentscope.extensions.model.openai.OpenAIChatModel

/**
 * 模型辅助工具类
 * 提供基于 ModelConfig 创建 ChatModel 的方法
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Project: harnax
 */
object ModelHelper {

    /**
     * 根据模型配置创建 ChatModel 实例
     *
     * @param chatModelConfig 模型配置，支持 DashScope、OpenAI、Ollama
     * @param enableThinking 是否启用思考模式
     * @param enableSearch 是否启用搜索
     * @return ChatModel 实例
     * @throws ModelErrorCode.MODEL_CREATE_FAILED 当模型创建失败时抛出
     */
    fun createChatModel(
        chatModelConfig: ChatModelConfig,
        enableThinking: Boolean? = null,
        enableSearch: Boolean? = null,
    ): ChatModelBase = try {
        when (chatModelConfig) {
            is DashScopeChatModelConfig -> buildDashScopeChatModel(chatModelConfig, enableThinking, enableSearch)
            is OpenAIChatModelConfig -> buildOpenAIChatModel(chatModelConfig)
            is OllamaChatModelConfig -> buildOllamaChatModel(chatModelConfig, enableThinking)
        }
    } catch (e: Exception) {
        throw ModelErrorCode.MODEL_CREATE_FAILED.format(e, chatModelConfig.modelName)
    }

    /**
     * 构建 DashScope ChatModel
     */
    private fun buildDashScopeChatModel(
        config: DashScopeChatModelConfig,
        enableThinking: Boolean?,
        enableSearch: Boolean?,
    ): ChatModelBase {
        val builder = DashScopeChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .stream(config.stream)
            .enableThinking(enableThinking ?: config.enableThinking)
            .enableSearch(enableSearch ?: config.enableSearch)
        // SDK 未将部分多模态模型（如 qwen3.7-max）纳入自动识别，
        // 需根据模型 vision 标记强制指定多模态端点，避免 DashScope 返回 url error
        if (config.forceMultimodalEndpoint) {
            builder.endpointType(EndpointType.MULTIMODAL)
        }
        config.baseUrl?.let { builder.baseUrl(it) }
        config.httpTransport?.let { builder.httpTransport(it) }
        config.options?.let { builder.defaultOptions(it) }
        return builder.build()
    }

    /**
     * 构建 OpenAI ChatModel
     */
    private fun buildOpenAIChatModel(
        config: OpenAIChatModelConfig,
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
     */
    private fun buildOllamaChatModel(
        config: OllamaChatModelConfig,
        enableThinking: Boolean?,
    ): ChatModelBase {
        val options = config.options ?: OllamaOptions.builder().build()
        if (enableThinking != null) {
            options.thinkOption =
                if (enableThinking) ThinkOption.ThinkBoolean.ENABLED else ThinkOption.ThinkBoolean.DISABLED
        }
        val builder = OllamaChatModel.builder()
            .modelName(config.modelName)
            .baseUrl(config.baseUrl)
        config.httpTransport?.let { builder.httpTransport(it) }
        config.options?.let { builder.defaultOptions(it) }
        return builder.build()
    }
}
