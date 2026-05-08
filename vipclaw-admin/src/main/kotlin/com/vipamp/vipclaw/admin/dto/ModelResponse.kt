package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.Model
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 模型响应对象
 */
@Schema(description = "模型响应对象")
data class ModelResponse(
    @Schema(description = "ID", example = "1")
    var id: Long? = null,
    @Schema(description = "名称", example = "GPT-4")
    var name: String? = null,
    @Schema(description = "模型名称", example = "gpt-4")
    var modelName: String? = null,
    @Schema(description = "模型供应商ID", example = "1")
    var providerId: Long? = null,
    @Schema(description = "模型供应商名称", example = "OpenAI")
    var providerName: String? = null,
    @Schema(description = "描述")
    var description: String? = null,
    @Schema(description = "模型类型（chat/embedding）", example = "chat")
    var modelType: String? = null,
    @Schema(description = "能力标签", example = "[\"reasoning\", \"tool\"]")
    var tags: List<String>? = null,
    @Schema(description = "是否支持联网", example = "0")
    var supportInternet: Int? = null,
    @Schema(description = "是否支持推理", example = "0")
    var supportReasoning: Int? = null,
    @Schema(description = "是否支持工具", example = "0")
    var supportTool: Int? = null,
    @Schema(description = "是否支持MCP", example = "0")
    var supportMcp: Int? = null,
    @Schema(description = "是否支持视觉", example = "0")
    var supportVision: Int? = null,
    @Schema(description = "价格（元/百万token）", example = "0.0000")
    var price: Double? = null,
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    var status: Int? = null,
    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    var isPublic: Int? = null,
    @Schema(description = "创建人", example = "admin")
    var creator: String? = null,
    @Schema(description = "创建时间", example = "2026-03-13 12:00:00")
    var createTime: LocalDateTime? = null,
    @Schema(description = "更新时间", example = "2026-03-13 12:00:00")
    var updateTime: LocalDateTime? = null,
) {
    companion object {
        fun fromEntity(model: Model): ModelResponse = ModelResponse(
            id = model.id,
            name = model.name,
            modelName = model.modelName,
            providerId = model.providerId,
            description = model.description,
            modelType = model.modelType,
            tags = calculateTags(model),
            supportInternet = model.supportInternet,
            supportReasoning = model.supportReasoning,
            supportTool = model.supportTool,
            supportMcp = model.supportMcp,
            supportVision = model.supportVision,
            price = model.price,
            status = model.status,
            isPublic = model.isPublic,
            creator = model.creator,
            createTime = model.createTime,
            updateTime = model.updateTime,
        )

        /**
         * 根据能力字段自动计算标签列表
         */
        private fun calculateTags(model: Model): List<String> {
            val tags = mutableListOf<String>()
            if (model.supportInternet == 1) tags.add("internet")
            if (model.supportReasoning == 1) tags.add("reasoning")
            if (model.supportTool == 1) tags.add("tool")
            if (model.supportMcp == 1) tags.add("mcp")
            if (model.supportVision == 1) tags.add("vision")
            return tags
        }
    }
}
