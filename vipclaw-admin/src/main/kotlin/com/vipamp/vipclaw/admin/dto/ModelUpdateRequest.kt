package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * Model update request object
 */
@Schema(description = "Model update request object")
data class ModelUpdateRequest(
    @field:Size(min = 1, max = 100, message = "Model name length must be between 1-100 characters")
    @Schema(description = "Model name", example = "GPT-4")
    val name: String? = null,

    @field:Size(min = 1, max = 100, message = "Model technical name length must be between 1-100 characters")
    @Schema(description = "Model technical name", example = "gpt-4")
    val modelName: String? = null,

    @Schema(description = "Model provider ID", example = "1")
    val providerId: Long? = null,

    @Schema(description = "Description", example = "OpenAI's most powerful multimodal model")
    val description: String? = null,

    @Schema(description = "Model type (chat/embedding)", example = "chat")
    val modelType: String? = null,

    @Schema(description = "Whether supports internet search", example = "0")
    val supportInternet: Int? = null,

    @Schema(description = "Whether supports reasoning", example = "0")
    val supportReasoning: Int? = null,

    @Schema(description = "Whether supports tools", example = "0")
    val supportTool: Int? = null,

    @Schema(description = "Whether supports MCP", example = "0")
    val supportMcp: Int? = null,

    @Schema(description = "Whether supports vision", example = "0")
    val supportVision: Int? = null,

    @Schema(description = "Price (CNY/million tokens)", example = "0.0000")
    @field:DecimalMin(value = "0.0", message = "Price cannot be less than 0")
    val price: Double? = null,

    @Schema(description = "Whether public (0:private 1:public)", example = "1")
    val isPublic: Int? = null,
)
