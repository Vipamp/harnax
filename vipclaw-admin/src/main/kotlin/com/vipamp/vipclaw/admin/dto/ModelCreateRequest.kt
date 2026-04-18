package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * 模型创建请求对象
 */
@Schema(description = "模型创建请求对象")
data class ModelCreateRequest(
    @Size(max = 100, message = "名称长度不能超过 100 个字符")
    val name: String? = null,
    @Size(max = 100, message = "模型名称长度不能超过 100 个字符")
    val modelName: String? = null,
    @Schema(description = "模型供应商ID")
    @NotNull(message = "模型供应商不能为空")
    val providerId: Long? = null,
    @Schema(description = "描述")
    val description: String? = null,
    @Schema(description = "模型类型（chat/embedding）")
    @NotBlank(message = "模型类型不能为空")
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
    val price: Double? = null,
    @Schema(description = "状态（0:禁用，1:启用）", example = "1")
    val status: Int? = null
)
