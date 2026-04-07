package com.vipamp.vipclaw.agent.adaptor.model

import io.agentscope.core.model.ChatModelBase
import io.agentscope.core.model.DashScopeChatModel
import io.agentscope.core.model.OllamaChatModel
import io.agentscope.core.model.OpenAIChatModel

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
     * @param modelConfig 模型配置，支持 DashScope、OpenAI、Ollama
     * @return ChatModel 实例
     * @throws ModelErrorCode.MODEL_CREATE_FAILED 当模型创建失败时抛出
     */
    fun createChatModel(modelConfig: ModelConfig): ChatModelBase {
        return try {
            when (modelConfig) {
                is DashScopeModelConfig -> buildDashScopeChatModel(modelConfig)
                is OpenAIModelConfig -> buildOpenAIChatModel(modelConfig)
                is OllamaModelConfig -> buildOllamaChatModel(modelConfig)
            }
        } catch (e: Exception) {
            throw ModelErrorCode.MODEL_CREATE_FAILED.format(e, modelConfig.modelName)
        }
    }

    /**
     * 构建 DashScope ChatModel
     *
     * @param config DashScope 模型配置
     * @return DashScopeChatModel 实例
     */
    private fun buildDashScopeChatModel(config: DashScopeModelConfig): ChatModelBase {
        val builder = DashScopeChatModel.builder()
            .apiKey(config.apiKey)
            .modelName(config.modelName)
            .stream(config.stream)
            .enableThinking(config.enableThinking)
            .enableSearch(config.enableSearch)
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
    private fun buildOpenAIChatModel(config: OpenAIModelConfig): ChatModelBase {
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
    private fun buildOllamaChatModel(config: OllamaModelConfig): ChatModelBase {
        val builder = OllamaChatModel.builder()
            .modelName(config.modelName)
            .baseUrl(config.baseUrl)
        config.httpTransport?.let { builder.httpTransport(it) }
        config.options?.let { builder.defaultOptions(it) }
        return builder.build()
    }
}
