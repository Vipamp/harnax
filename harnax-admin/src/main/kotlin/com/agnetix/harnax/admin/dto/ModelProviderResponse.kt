package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.admin.entity.ModelProvider
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * Model provider response object
 */
@Schema(description = "Model provider response object")
data class ModelProviderResponse(
    @Schema(description = "ID", example = "1")
    var id: Long = 0,
    @Schema(description = "Provider type (dashscope/openai/ollama)", example = "dashscope")
    var type: String = "",
    @Schema(description = "Name", example = "Alibaba Cloud DashScope")
    var name: String = "",
    @Schema(description = "Description", example = "Large language model API service provided by Alibaba Cloud")
    var description: String? = null,

    @field:Size(max = 500, message = "API key length cannot exceed 500 characters")
    @Schema(description = "API key (sensitive information)", example = "sk-xxxxxxxxxxxxxxxx")
    val apiKey: String? = null,

    @field:Pattern(
        regexp = "^(https?:\\/\\/)?([\\w.-]+)(:\\d+)?(\\/[^\\s]*)?$|^$",
        message = "Invalid API URL format",
    )
    @Schema(description = "API base URL (can be empty)", example = "https://dashscope.aliyuncs.com/compatible-mode/v1")
    val baseUrl: String? = null,

    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    var status: Int = 1,
    @Schema(description = "Whether public (0:no, 1:yes)", example = "1")
    var isPublic: Int = 0,
    @Schema(description = "Creator", example = "admin")
    var creator: String = "",
    @Schema(description = "Creation time", example = "2026-03-13 12:00:00")
    var createTime: LocalDateTime = LocalDateTime.now(),
    @Schema(description = "Update time", example = "2026-03-13 12:00:00")
    var updateTime: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        fun fromEntity(provider: ModelProvider): ModelProviderResponse = ModelProviderResponse(
            id = provider.id,
            type = provider.type,
            name = provider.name,
            description = provider.description,
            apiKey = maskApiKey(provider.apiKey),
            baseUrl = provider.baseUrl,
            status = provider.status,
            isPublic = provider.isPublic,
            creator = provider.creator,
            createTime = provider.createTime,
            updateTime = provider.updateTime,
        )

        /**
         * API Key masking
         * Rule: first 2 chars + **** + last 4 chars
         */
        private fun maskApiKey(apiKey: String?): String? {
            if (apiKey.isNullOrEmpty()) return apiKey
            if (apiKey.length <= 6) return "****"
            return apiKey.substring(0, 2) + "****" + apiKey.substring(apiKey.length - 4)
        }
    }
}
