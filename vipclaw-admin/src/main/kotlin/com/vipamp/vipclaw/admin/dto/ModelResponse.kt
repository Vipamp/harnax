package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.Model
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Model response object
 */
@Schema(description = "Model response object")
data class ModelResponse(
    @Schema(description = "ID", example = "1")
    var id: Long? = null,
    @Schema(description = "Name", example = "GPT-4")
    var name: String? = null,
    @Schema(description = "Model name", example = "gpt-4")
    var modelName: String? = null,
    @Schema(description = "Model provider ID", example = "1")
    var providerId: Long? = null,
    @Schema(description = "Model provider name", example = "OpenAI")
    var providerName: String? = null,
    @Schema(description = "Description")
    var description: String? = null,
    @Schema(description = "Model type (chat/embedding)", example = "chat")
    var modelType: String? = null,
    @Schema(description = "Capability tags", example = "[\"reasoning\", \"tool\"]")
    var tags: List<String>? = null,
    @Schema(description = "Whether supports internet search", example = "0")
    var supportInternet: Int? = null,
    @Schema(description = "Whether supports reasoning", example = "0")
    var supportReasoning: Int? = null,
    @Schema(description = "Whether supports tools", example = "0")
    var supportTool: Int? = null,
    @Schema(description = "Whether supports MCP", example = "0")
    var supportMcp: Int? = null,
    @Schema(description = "Whether supports vision", example = "0")
    var supportVision: Int? = null,
    @Schema(description = "Price (CNY/million tokens)", example = "0.0000")
    var price: Double? = null,
    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    var status: Int? = null,
    @Schema(description = "Whether public (0:no, 1:yes)", example = "1")
    var isPublic: Int? = null,
    @Schema(description = "Creator", example = "admin")
    var creator: String? = null,
    @Schema(description = "Creation time", example = "2026-03-13 12:00:00")
    var createTime: LocalDateTime? = null,
    @Schema(description = "Update time", example = "2026-03-13 12:00:00")
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
         * Automatically calculate tag list based on capability fields
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
