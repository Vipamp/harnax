package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.admin.entity.Agent
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Agent response DTO
 */
@Schema(description = "Agent response object")
data class AgentResponse(
    @Schema(description = "ID", example = "1")
    var id: Long? = null,

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

    @Schema(description = "Chat model price (per million tokens)", example = "50.0")
    var modelPrice: Double? = null,

    @Schema(description = "MCP server list")
    var mcpList: List<McpItem>? = null,

    @Schema(description = "Skill list")
    var skillList: List<SkillItem>? = null,

    @Schema(description = "Session list")
    var sessionList: List<SessionItem>? = null,

    @Schema(description = "Owner", example = "admin")
    var owner: String? = null,

    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    var status: Int? = null,

    @Schema(description = "Whether public (0:no, 1:yes)", example = "1")
    var isPublic: Int? = null,

    @Schema(description = "Creator", example = "admin")
    var creator: String? = null,

    @Schema(description = "Creation time", example = "2026-03-18 12:00:00")
    var createTime: LocalDateTime? = null,

    @Schema(description = "Update time", example = "2026-03-18 12:00:00")
    var updateTime: LocalDateTime? = null,

    @Schema(description = "Associated session count", example = "5")
    var sessionCount: Int? = null,
) {

    companion object {
        fun fromEntity(agent: Agent): AgentResponse {
            val response = AgentResponse(
                id = agent.id,
                name = agent.name,
                description = agent.description,
                systemPrompt = agent.systemPrompt,
                modelId = agent.modelId,
                owner = agent.owner,
                status = agent.status,
                isPublic = agent.isPublic,
                creator = agent.creator,
                createTime = agent.createTime,
                updateTime = agent.updateTime,
            )

            return response
        }
    }

    @Schema(description = "MCP item")
    data class McpItem(
        @Schema(description = "MCP ID", example = "1")
        var mcpId: Long? = null,

        @Schema(description = "MCP name", example = "filesystem")
        var mcpName: String? = null,

        @Schema(description = "MCP description", example = "File system service")
        var mcpDescription: String? = null,

        @Schema(description = "Whether allow skip", example = "true")
        var enableSkip: String? = null,
    )

    data class McpConfigInternal(
        var id: Long? = null,
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

    @Schema(description = "Session item")
    data class SessionItem(
        @Schema(description = "Session ID", example = "1")
        var id: Long? = null,

        @Schema(description = "Session title", example = "My session")
        var title: String? = null,

        @Schema(description = "Session description", example = "This is a session")
        var sessionDescription: String? = null,

        @Schema(description = "Session UUID", example = "session-123")
        var sessionId: String? = null,
    )
}
