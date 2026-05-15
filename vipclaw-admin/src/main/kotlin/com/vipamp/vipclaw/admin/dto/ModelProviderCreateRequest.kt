package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * Model provider creation request object
 */
@Schema(description = "Model provider creation request object")
data class ModelProviderCreateRequest(
    @field:NotBlank(message = "Provider type cannot be empty")
    @field:Size(min = 1, max = 50, message = "Provider type length must be between 1-50 characters")
    @field:Pattern(regexp = "^[a-z0-9_]+$", message = "Provider type can only contain lowercase letters, numbers and underscores")
    @Schema(description = "Provider technical type", example = "dashscope", requiredMode = Schema.RequiredMode.REQUIRED)
    val type: String,

    @field:NotBlank(message = "Name cannot be empty")
    @field:Size(min = 1, max = 100, message = "Name length must be between 1-100 characters")
    @Schema(description = "Provider name", example = "Alibaba Cloud Bailian", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,

    @field:Size(max = 500, message = "Description length cannot exceed 500 characters")
    @Schema(description = "Provider description", example = "Large language model API service provided by Alibaba Cloud")
    val description: String? = null,

    @field:Size(max = 500, message = "API key length cannot exceed 500 characters")
    @Schema(description = "API key (sensitive information)", example = "sk-xxxxxxxxxxxxxxxx")
    val apiKey: String? = null,

    @field:Pattern(
        regexp = "^(https?:\\/\\/)?([\\w.-]+)(:\\d+)?(\\/[^\\s]*)?$|^$",
        message = "Invalid API URL format",
    )
    @Schema(description = "API base URL (can be empty)", example = "https://dashscope.aliyuncs.com/compatible-mode/v1")
    val baseUrl: String? = null,

    @Schema(description = "Whether public (0:private 1:public)", example = "1")
    val isPublic: Int? = 1,
)
