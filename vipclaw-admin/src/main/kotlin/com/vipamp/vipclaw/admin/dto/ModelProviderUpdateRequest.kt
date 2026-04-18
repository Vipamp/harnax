package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * 模型服务商更新请求对象
 */
@Schema(description = "模型服务商更新请求对象")
data class ModelProviderUpdateRequest(
    @Schema(description = "ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long? = null,
    @Schema(description = "服务商名称（dashscope/openai/ollama）")
    @Size(max = 50, message = "服务商名称长度不能超过 50 个字符")
    val name: String? = null,
    @Schema(description = "显示名称")
    @Size(max = 100, message = "显示名称长度不能超过 100 个字符")
    val displayName: String? = null,
    @Schema(description = "API 密钥", example = "sk-xxxxxxxxxxxxxxxx")
    val apiKey: String? = null,
    @Schema(description = "API 地址", example = "https://dashscope.aliyuncs.com/compatible-mode/v1")
    val baseUrl: String? = null,
    @Schema(description = "状态（0:禁用，1:启用）", example = "1")
    val status: Int? = null
)
