package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*
import java.time.LocalDateTime

/**
 * 模型服务商响应对象
 */
@Schema(description = "模型服务商响应对象")
data class ModelProviderResponse(
    @Schema(description = "ID", example = "1")
    val id: Long? = null,
    @Schema(description = "服务商名称（dashscope/openai/ollama）", example = "dashscope")
    val name: String? = null,
    @Schema(description = "显示名称", example = "阿里云 DashScope")
    val displayName: String? = null,
    @Schema(description = "API 密钥（脱敏显示）", example = "sk-****xxxx")
    val apiKey: String? = null,
    @Schema(description = "API 地址", example = "https://dashscope.aliyuncs.com/compatible-mode/v1")
    val baseUrl: String? = null,
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    val status: Int? = null,
    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    val isPublic: Int? = null,
    @Schema(description = "创建人", example = "admin")
    val creator: String? = null,
    @Schema(description = "创建时间", example = "2026-03-13 12:00:00")
    val createTime: LocalDateTime? = null,
    @Schema(description = "更新时间", example = "2026-03-13 12:00:00")
    val updateTime: LocalDateTime? = null
)
