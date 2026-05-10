package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 模型服务商更新请求对象
 */
@Schema(description = "模型服务商更新请求对象")
data class ModelProviderUpdateRequest(
    @field:Size(min = 1, max = 50, message = "供应商类型长度必须在 1-50 个字符之间")
    @field:Pattern(regexp = "^[a-z0-9_]+$", message = "供应商类型只能包含小写字母、数字和下划线")
    @Schema(description = "供应商技术类型", example = "dashscope")
    val type: String? = null,

    @field:Size(min = 1, max = 100, message = "名称长度必须在 1-100 个字符之间")
    @Schema(description = "供应商名称", example = "阿里云百炼")
    val name: String? = null,

    @field:Size(max = 500, message = "描述长度不能超过 500 个字符")
    @Schema(description = "服务商描述（为空则不修改）", example = "阿里云提供的大语言模型 API 服务")
    val description: String? = null,

    @field:Size(max = 500, message = "API 密钥长度不能超过 500 个字符")
    @Schema(description = "API 密钥（为空则不修改）", example = "sk-xxxxxxxxxxxxxxxx")
    val apiKey: String? = null,

    @field:Pattern(
        regexp = "^(https?:\\/\\/)?([\\w.-]+)+(:\\d+)?(\\/[^\\s]*)?$",
        message = "API 地址格式不正确",
    )
    @Schema(description = "API 基础地址", example = "https://dashscope.aliyuncs.com/compatible-mode/v1")
    val baseUrl: String? = null,

    @Schema(description = "是否公开（0:私有 1:公开）", example = "1")
    val isPublic: Int? = null,
)
