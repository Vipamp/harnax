package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * Model creation request object
 */
@Schema(description = "Model creation request object")
data class ModelCreateRequest(
    @field:NotBlank(message = "Model name cannot be empty")
    @field:Size(min = 1, max = 100, message = "Model name length must be between 1-100 characters")
    @Schema(description = "Model name", example = "GPT-4", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,

    @field:NotBlank(message = "Model technical name cannot be empty")
    @field:Size(min = 1, max = 100, message = "Model technical name length must be between 1-100 characters")
    @Schema(description = "Model technical name", example = "gpt-4", requiredMode = Schema.RequiredMode.REQUIRED)
    val modelName: String,

    @Schema(description = "Model provider ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = "Model provider cannot be empty")
    val providerId: Long,

    @Schema(description = "Description", example = "OpenAI's most powerful multimodal model")
    val description: String? = null,

    @Schema(description = "Model type (chat/embedding)", example = "chat", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = "Model type cannot be empty")
    val modelType: String,

    @Schema(description = "Whether supports internet search", example = "0")
    val supportInternet: Int? = 0,

    @Schema(description = "Whether supports reasoning", example = "0")
    val supportReasoning: Int? = 0,

    @Schema(description = "Whether supports tools", example = "0")
    val supportTool: Int? = 0,

    @Schema(description = "Whether supports MCP", example = "0")
    val supportMcp: Int? = 0,

    @Schema(description = "Whether supports vision", example = "0")
    val supportVision: Int? = 0,

    @Schema(description = "Price (CNY/million tokens)", example = "0.0000")
    @field:DecimalMin(value = "0.0", message = "Price cannot be less than 0")
    val price: Double? = 0.0,

    @Schema(description = "Whether public (0:private 1:public)", example = "1")
    val isPublic: Int? = 1,
)
