package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * 模型更新请求对象
 */
@Schema(description = "模型更新请求对象")
data class ModelUpdateRequest(
    @field:Size(min = 1, max = 100, message = "模型名称长度必须在 1-100 个字符之间")
    @Schema(description = "模型名称", example = "GPT-4")
    val name: String? = null,

    @field:Size(min = 1, max = 100, message = "模型技术名称长度必须在 1-100 个字符之间")
    @Schema(description = "模型技术名称", example = "gpt-4")
    val modelName: String? = null,

    @Schema(description = "模型供应商ID", example = "1")
    val providerId: Long? = null,

    @Schema(description = "描述", example = "OpenAI 最强的多模态模型")
    val description: String? = null,

    @Schema(description = "模型类型（chat/embedding）", example = "chat")
    val modelType: String? = null,

    @Schema(description = "是否支持联网", example = "0")
    val supportInternet: Int? = null,

    @Schema(description = "是否支持推理", example = "0")
    val supportReasoning: Int? = null,

    @Schema(description = "是否支持工具", example = "0")
    val supportTool: Int? = null,

    @Schema(description = "是否支持MCP", example = "0")
    val supportMcp: Int? = null,

    @Schema(description = "是否支持视觉", example = "0")
    val supportVision: Int? = null,

    @Schema(description = "价格（元/百万token）", example = "0.0000")
    @field:DecimalMin(value = "0.0", message = "价格不能小于 0")
    val price: Double? = null,

    @Schema(description = "是否公开（0:私有 1:公开）", example = "1")
    val isPublic: Int? = null,
)
