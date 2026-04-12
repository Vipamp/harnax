package com.vipamp.vipclaw.agent.adaptor.model

import io.agentscope.core.model.GenerateOptions
import io.agentscope.core.model.ollama.OllamaOptions
import io.agentscope.core.model.transport.HttpTransport

/**
 * 模型配置接口
 * 支持多种模型提供商的配置
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Description: ModelConfig
 * @Project: vipclaw
 */
sealed interface ChatModelConfig {
    val modelName: String
}

/**
 * DashScope 模型配置
 * 阿里云 LLM 平台，提供通义千问系列模型
 */
data class DashScopeChatModelConfig(
    override val modelName: String,
    val apiKey: String,
    val baseUrl: String? = null,
    val stream: Boolean = true,
    val enableThinking: Boolean = true,
    val enableSearch: Boolean = false,
    val httpTransport: HttpTransport? = null,
    val options: GenerateOptions? = null,
    val encrypt: Boolean = false
) : ChatModelConfig

/**
 * OpenAI 模型配置
 * OpenAI 模型及兼容 API（DeepSeek、vLLM 等）
 */
data class OpenAIChatModelConfig(
    override val modelName: String,
    val apiKey: String,
    val baseUrl: String? = null,
    val stream: Boolean = true,
    val endpointPath: String? = null,
    val httpTransport: HttpTransport? = null,
    val options: GenerateOptions? = null
) : ChatModelConfig

/**
 * Ollama 模型配置
 * 自托管开源 LLM 平台
 */
data class OllamaChatModelConfig(
    override val modelName: String,
    val baseUrl: String = "http://localhost:11434",
    val httpTransport: HttpTransport? = null,
    val options: OllamaOptions? = null
) : ChatModelConfig
