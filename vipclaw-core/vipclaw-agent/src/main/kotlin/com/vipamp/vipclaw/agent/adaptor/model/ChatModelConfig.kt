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
sealed interface ModelConfig {
    val modelName: String
}

/**
 * DashScope 模型配置
 * 阿里云 LLM 平台，提供通义千问系列模型
 */
data class DashScopeModelConfig(
    override val modelName: String,
    val apiKey: String,
    val baseUrl: String? = null,
    val stream: Boolean = true,
    val enableThinking: Boolean = true,
    val enableSearch: Boolean = false,
    val httpTransport: HttpTransport? = null,
    val options: GenerateOptions? = null,
    val encrypt: Boolean = false
) : ModelConfig

/**
 * OpenAI 模型配置
 * OpenAI 模型及兼容 API（DeepSeek、vLLM 等）
 */
data class OpenAIModelConfig(
    override val modelName: String,
    val apiKey: String,
    val baseUrl: String? = null,
    val stream: Boolean = true,
    val endpointPath: String? = null,
    val httpTransport: HttpTransport? = null,
    val options: GenerateOptions? = null
) : ModelConfig

/**
 * Ollama 模型配置
 * 自托管开源 LLM 平台
 */
data class OllamaModelConfig(
    override val modelName: String,
    val baseUrl: String = "http://localhost:11434",
    val stream: Boolean = true,
    val httpTransport: HttpTransport? = null,
    val options: OllamaOptions? = null
) : ModelConfig
