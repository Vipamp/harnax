package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.ModelProvider
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 模型服务商响应对象
 */
@Schema(description = "模型服务商响应对象")
data class ModelProviderResponse(
    @Schema(description = "ID", example = "1")
    var id: Long? = null,
    @Schema(description = "服务商名称（dashscope/openai/ollama）", example = "dashscope")
    var name: String? = null,
    @Schema(description = "显示名称", example = "阿里云 DashScope")
    var displayName: String? = null,
    @Schema(description = "API 密钥（脱敏显示）", example = "sk-****xxxx")
    var apiKey: String? = null,
    @Schema(description = "API 地址", example = "https://dashscope.aliyuncs.com/compatible-mode/v1")
    var baseUrl: String? = null,
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    var status: Int? = null,
    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    var isPublic: Int? = null,
    @Schema(description = "创建人", example = "admin")
    var creator: String? = null,
    @Schema(description = "创建时间", example = "2026-03-13 12:00:00")
    var createTime: LocalDateTime? = null,
    @Schema(description = "更新时间", example = "2026-03-13 12:00:00")
    var updateTime: LocalDateTime? = null
) {
    companion object {
        fun fromEntity(provider: ModelProvider): ModelProviderResponse {
            return ModelProviderResponse(
                id = provider.id,
                name = provider.name,
                displayName = provider.displayName,
                apiKey = maskApiKey(provider.apiKey),
                baseUrl = provider.baseUrl,
                status = provider.status,
                isPublic = provider.isPublic,
                creator = provider.creator,
                createTime = provider.createTime,
                updateTime = provider.updateTime
            )
        }
        
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
