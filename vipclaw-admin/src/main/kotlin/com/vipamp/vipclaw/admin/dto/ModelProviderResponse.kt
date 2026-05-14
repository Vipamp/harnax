package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.ModelProvider
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

/**
 * 模型服务商响应对象
 */
@Schema(description = "模型服务商响应对象")
data class ModelProviderResponse(
    @Schema(description = "ID", example = "1")
    var id: Long = 0,
    @Schema(description = "服务商类型（dashscope/openai/ollama）", example = "dashscope")
    var type: String = "",
    @Schema(description = "名称", example = "阿里云 DashScope")
    var name: String = "",
    @Schema(description = "描述", example = "阿里云提供的大语言模型 API 服务")
    var description: String? = null,

    @field:Size(max = 500, message = "API 密钥长度不能超过 500 个字符")
    @Schema(description = "API 密钥（敏感信息）", example = "sk-xxxxxxxxxxxxxxxx")
    val apiKey: String? = null,

    @field:Pattern(
        regexp = "^(https?:\\/\\/)?([\\w.-]+)(:\\d+)?(\\/[^\\s]*)?$|^$",
        message = "API URL格式不正确",
    )
    @Schema(description = "API 基础地址（可为空）", example = "https://dashscope.aliyuncs.com/compatible-mode/v1")
    val baseUrl: String? = null,

    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    var status: Int = 1,
    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    var isPublic: Int = 0,
    @Schema(description = "创建人", example = "admin")
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
         * API Key 脱敏处理
         * 规则：前 2 位 + **** + 后 4 位
         */
        private fun maskApiKey(apiKey: String?): String? {
            if (apiKey.isNullOrEmpty()) return apiKey
            if (apiKey.length <= 6) return "****"
            return apiKey.substring(0, 2) + "****" + apiKey.substring(apiKey.length - 4)
        }
    }
}
