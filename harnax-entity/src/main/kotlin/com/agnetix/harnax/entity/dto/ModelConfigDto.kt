package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Full model + provider configuration returned by admin internal API.
 * Eliminates the need for agent-service to query model/provider tables directly.
 */
@Schema(description = "Model configuration with provider details")
data class ModelConfigDto(
    @Schema(description = "Model ID")
    val modelId: Long,

    @Schema(description = "Model name (e.g. qwen-max, gpt-4o)")
    val modelName: String,

    @Schema(description = "Model type (chat/embedding)")
    val modelType: String = "chat",

    @Schema(description = "Provider type (dashscope/openai/ollama)")
    val providerType: String,

    @Schema(description = "API key for the provider")
    val apiKey: String? = null,

    @Schema(description = "Custom base URL (null for provider default)")
    val baseUrl: String? = null,

    @Schema(description = "Supports internet search (0:no, 1:yes)")
    val supportInternet: Int = 0,

    @Schema(description = "Supports reasoning/thinking (0:no, 1:yes)")
    val supportReasoning: Int = 0,

    @Schema(description = "Supports tool calling (0:no, 1:yes)")
    val supportTool: Int = 0,

    @Schema(description = "Supports vision/image input (0:no, 1:yes)")
    val supportVision: Int = 0,

    @Schema(description = "Supports MCP (0:no, 1:yes)")
    val supportMcp: Int = 0,
)
