package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Session response object
 */
@Schema(description = "Session response object")
data class SessionResponse(
    @Schema(description = "ID", example = "1")
    var id: Long? = null,
    @Schema(description = "Session name", example = "My session")
    var title: String? = null,
    @Schema(description = "Session description", example = "This is a session description")
    var sessionDescription: String? = null,
    @Schema(description = "Session ID", example = "session-123")
    var sessionId: String? = null,
    @Schema(description = "Associated agent ID", example = "1")
    var agentId: Long? = null,
    @Schema(description = "Agent name", example = "assistant")
    var name: String? = null,
    @Schema(description = "Agent description")
    var description: String? = null,
    @Schema(description = "System prompt (Markdown supported)")
    var systemPrompt: String? = null,
    @Schema(description = "Chat model ID", example = "1")
    var modelId: Long? = null,
    @Schema(description = "Chat model name", example = "GPT-4")
    var modelName: String? = null,
    @Schema(description = "Model price (CNY/million tokens)", example = "15.0")
    var modelPrice: Double? = null,
    @Schema(description = "Enable deep thinking (0:no, 1:yes)", example = "0")
    var enableThink: Int? = null,
    @Schema(description = "Enable web search (0:no, 1:yes)", example = "0")
    var enableSearch: Int? = null,
    @Schema(description = "Enable planning (0:no, 1:yes)", example = "0")
    var enablePlan: Int? = null,
    @Schema(description = "MCP server list")
    var mcpList: List<McpItem> = listOf(),
    @Schema(description = "Skill list")
    var skillList: List<SkillItem> = emptyList(),
    @Schema(description = "Owner", example = "admin")
    var owner: String? = null,
    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    var status: Int? = null,
    @Schema(description = "Whether public (0:no, 1:yes)", example = "1")
    var isPublic: Int? = null,
    @Schema(description = "Creator", example = "admin")
    var creator: String? = null,
    @Schema(description = "Creation time", example = "2026-03-25 12:00:00")
    var createTime: LocalDateTime? = null,
    @Schema(description = "Update time", example = "2026-03-25 12:00:00")
    var updateTime: LocalDateTime? = null,
) {
    @Schema(description = "MCP item")
    data class McpItem(
        @Schema(description = "MCP ID", example = "1")
        var mcpId: Long? = null,
        @Schema(description = "MCP name", example = "filesystem")
        var mcpName: String? = null,
        @Schema(description = "MCP description", example = "File system service")
        var mcpDescription: String? = null,
        @Schema(description = "Whether allow to skip", example = "true")
        var enableSkip: String? = null,
    )

    @Schema(description = "Skill item")
    data class SkillItem(
        @Schema(description = "Repository ID", example = "1")
        var repositoryId: Long? = null,
        @Schema(description = "Repository name", example = "qoder-skills")
        var repositoryName: String? = null,
        @Schema(description = "Skill ID", example = "1")
        var skillId: Long? = null,
        @Schema(description = "Skill name", example = "code-review")
        var skillName: String? = null,
        @Schema(description = "Skill description", example = "Code review skill")
        var skillDescription: String? = null,
    )
}
