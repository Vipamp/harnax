package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * Model creation request object
 */
@Schema(description = "Model creation request object")
data class ModelCreateRequest(
    @field:NotBlank(message = "Model name不能为空")
    @field:Size(min = 1, max = 100, message = "Model name长度必须在 1-100 个字符之间")
    @Schema(description = "Model name", example = "GPT-4", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,

    @field:NotBlank(message = "模型技术名称不能为空")
    @field:Size(min = 1, max = 100, message = "模型技术名称长度必须在 1-100 个字符之间")
    @Schema(description = "模型技术名称", example = "gpt-4", requiredMode = Schema.RequiredMode.REQUIRED)
    val modelName: String,

    @Schema(description = "模型供应商ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = "模型供应商不能为空")
    val providerId: Long,

    @Schema(description = "描述", example = "OpenAI 最强的多模态模型")
    val description: String? = null,

    @Schema(description = "Model type（chat/embedding）", example = "chat", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotBlank(message = "Model type不能为空")
    val modelType: String,

    @Schema(description = "是否支持联网", example = "0")
    val supportInternet: Int? = 0,

    @Schema(description = "是否支持推理", example = "0")
    val supportReasoning: Int? = 0,

    @Schema(description = "是否支持工具", example = "0")
    val supportTool: Int? = 0,

    @Schema(description = "是否支持MCP", example = "0")
    val supportMcp: Int? = 0,

    @Schema(description = "是否支持视觉", example = "0")
    val supportVision: Int? = 0,

    @Schema(description = "价格（元/百万token）", example = "0.0000")
    @field:DecimalMin(value = "0.0", message = "价格不能小于 0")
    val price: Double? = 0.0,

    @Schema(description = "是否公开（0:私有 1:公开）", example = "1")
    val isPublic: Int? = 1,
)
