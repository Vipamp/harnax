package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*
import java.time.LocalDateTime

/**
 * 模型响应对象
 */
@Schema(description = "模型响应对象")
data class ModelResponse(
    @Schema(description = "ID", example = "1")
    val id: Long? = null,
    @Schema(description = "名称", example = "GPT-4")
    val name: String? = null,
    @Schema(description = "模型名称", example = "gpt-4")
    val modelName: String? = null,
    @Schema(description = "模型供应商ID", example = "1")
    val providerId: Long? = null,
    @Schema(description = "模型供应商名称", example = "OpenAI")
    val providerName: String? = null,
    @Schema(description = "描述")
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
    val price: Double? = null,
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
