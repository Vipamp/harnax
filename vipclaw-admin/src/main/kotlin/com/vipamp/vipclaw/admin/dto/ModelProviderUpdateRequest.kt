package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * Model provider update request object
 */
@Schema(description = "Model provider update request object")
data class ModelProviderUpdateRequest(
    @field:Size(min = 1, max = 50, message = "Provider type length must be between 1-50 characters")
    @field:Pattern(regexp = "^[a-z0-9_]+$", message = "Provider type can only contain lowercase letters, numbers and underscores")
    @Schema(description = "Provider technical type", example = "dashscope")
    val type: String? = null,

    @field:Size(min = 1, max = 100, message = "Name length must be between 1-100 characters")
    @Schema(description = "Provider name", example = "Alibaba Cloud Bailian")
    val name: String? = null,

    @field:Size(max = 500, message = "Description length cannot exceed 500 characters")
    @Schema(description = "Provider description (empty means no modification)", example = "Large language model API service provided by Alibaba Cloud")
    val description: String? = null,

    @field:Size(max = 500, message = "API key length cannot exceed 500 characters")
    @Schema(description = "API key (empty means no modification)", example = "sk-xxxxxxxxxxxxxxxx")
    val apiKey: String? = null,

    @field:Pattern(
        regexp = "^(https?:\\/\\/)?([\\w.-]+)+(:\\d+)?(\\/[^\\s]*)?$",
        message = "Invalid API URL format",
    )
    @Schema(description = "API base URL", example = "https://dashscope.aliyuncs.com/compatible-mode/v1")
    val baseUrl: String? = null,

    @Schema(description = "Whether public (0:private 1:public)", example = "1")
    val isPublic: Int? = null,
)
