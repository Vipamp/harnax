package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * 模型服务商创建请求对象
 */
@Schema(description = "模型服务商创建请求对象")
data class ModelProviderCreateRequest(
    @field:NotBlank(message = "供应商名称不能为空")
    @field:Size(min = 1, max = 50, message = "供应商名称长度必须在 1-50 个字符之间")
    @field:Pattern(regexp = "^[a-z0-9_]+$", message = "供应商名称只能包含小写字母、数字和下划线")
    @Schema(description = "供应商技术名称", example = "dashscope", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
    
    @field:NotBlank(message = "显示名称不能为空")
    @field:Size(min = 1, max = 100, message = "显示名称长度必须在 1-100 个字符之间")
    @Schema(description = "供应商显示名称", example = "阿里云百炼", requiredMode = Schema.RequiredMode.REQUIRED)
    val displayName: String,
    
    @field:Size(max = 500, message = "API 密钥长度不能超过 500 个字符")
    @Schema(description = "API 密钥（敏感信息）", example = "sk-xxxxxxxxxxxxxxxx")
    val apiKey: String? = null,
    
    @Schema(description = "API 基础地址", example = "https://dashscope.aliyuncs.com/compatible-mode/v1")
    val baseUrl: String? = null,
    
    @Schema(description = "是否公开（0:私有 1:公开）", example = "1")
    val isPublic: Int? = 1
)
