package com.agnetix.harnax.agent.adaptor.model

import io.agentscope.core.model.GenerateOptions
import io.agentscope.core.model.transport.HttpTransport
import io.agentscope.extensions.model.ollama.options.OllamaOptions

/**
 * 模型配置接口
 * 支持多种模型提供商的配置
 *
 * @Author: heqingsong
 * @Date: 2026/3/25
 * @Description: ModelConfig
 * @Project: harnax
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
    val encrypt: Boolean = false,
    /**
     * 强制使用多模态端点（multimodal-generation）。
     * 部分多模态模型（如 qwen3.7-max）未被 SDK 自动识别为多模态，
     * 走 text-generation 端点时 DashScope 会返回 "url error"，
     * 此时需由模型的 support_vision 标记驱动强制切换。
     */
    val forceMultimodalEndpoint: Boolean = false,
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
    val options: GenerateOptions? = null,
) : ChatModelConfig

/**
 * Ollama 模型配置
 * 自托管开源 LLM 平台
 */
data class OllamaChatModelConfig(
    override val modelName: String,
    val baseUrl: String = "http://localhost:11434",
    val httpTransport: HttpTransport? = null,
    val options: OllamaOptions? = null,
) : ChatModelConfig
