package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

/**
 * 模型更新请求对象
 */
@Schema(description = "模型更新请求对象")
data class ModelUpdateRequest(
    @Schema(description = "ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long = 0,
    @Schema(description = "名称")
    @Size(max = 100, message = "名称长度不能超过 100 个字符")
    val name: String = "",
    @Schema(description = "模型名称")
    @Size(max = 100, message = "模型名称长度不能超过 100 个字符")
    val modelName: String = "",
    @Schema(description = "模型供应商ID", example = "1")
    val providerId: Long = 0,
    @Schema(description = "描述")
    val description: String = "",
    @Schema(description = "模型类型（chat/embedding）", example = "chat")
    val modelType: String = "chat",
    @Schema(description = "是否支持联网", example = "0")
    val supportInternet: Int = 0,
    @Schema(description = "是否支持推理", example = "0")
    val supportReasoning: Int = 0,
    @Schema(description = "是否支持工具", example = "0")
    val supportTool: Int = 0,
    @Schema(description = "是否支持MCP", example = "0")
    val supportMcp: Int = 0,
    @Schema(description = "是否支持视觉", example = "0")
    val supportVision: Int = 0,
    @Schema(description = "价格（元/百万token）", example = "0.0000")
    val price: Double = 0.0,
    @Schema(description = "状态（0:禁用，1:启用）", example = "1")
    val status: Int = 1
)
