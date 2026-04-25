package com.vipamp.vipclaw.admin.dto

import com.vipamp.vipclaw.admin.entity.Agent
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 智能体响应 DTO
 */
@Schema(description = "智能体响应对象")
data class AgentResponse(
    @Schema(description = "ID", example = "1")
    var id: Long? = null,

    @Schema(description = "智能体名称", example = "assistant")
    var name: String? = null,

    @Schema(description = "智能体描述")
    var description: String? = null,

    @Schema(description = "系统提示词(支持 Markdown)")
    var systemPrompt: String? = null,

    @Schema(description = "对话模型 ID", example = "1")
    var modelId: Long? = null,

    @Schema(description = "对话模型名称", example = "GPT-4")
    var modelName: String? = null,

    @Schema(description = "对话模型价格(每百万 token)", example = "50.0")
    var modelPrice: Double? = null,

    @Schema(description = "MCP 服务列表")
    var mcpList: List<McpItem>? = null,

    @Schema(description = "技能列表")
    var skillList: List<SkillItem>? = null,

    @Schema(description = "会话列表")
    var sessionList: List<SessionItem>? = null,

    @Schema(description = "所有者", example = "admin")
    var owner: String? = null,

    @Schema(description = "是否启用(0:禁用,1:启用)", example = "1")
    var status: Int? = null,

    @Schema(description = "是否公开(0:否,1:是)", example = "1")
    var isPublic: Int? = null,

    @Schema(description = "创建人", example = "admin")
    var creator: String? = null,

    @Schema(description = "创建时间", example = "2026-03-18 12:00:00")
    var createTime: LocalDateTime? = null,

    @Schema(description = "更新时间", example = "2026-03-18 12:00:00")
    var updateTime: LocalDateTime? = null,

    @Schema(description = "关联会话数量", example = "5")
    var sessionCount: Int? = null
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
                updateTime = agent.updateTime
            )

            return response
        }
    }

    @Schema(description = "MCP 项")
    data class McpItem(
        @Schema(description = "MCP ID", example = "1")
        var mcpId: Long? = null,

        @Schema(description = "MCP 名称", example = "filesystem")
        var mcpName: String? = null,

        @Schema(description = "MCP 描述", example = "文件系统服务")
        var mcpDescription: String? = null,

        @Schema(description = "是否允许跳过", example = "true")
        var enableSkip: String? = null
    )

    data class McpConfigInternal(
        var id: Long? = null,
        var enableSkip: String? = null
    )

    @Schema(description = "技能项")
    data class SkillItem(
        @Schema(description = "仓库 ID", example = "1")
        var repositoryId: Long? = null,

        @Schema(description = "仓库名称", example = "qoder-skills")
        var repositoryName: String? = null,

        @Schema(description = "技能 ID", example = "1")
        var skillId: Long? = null,

        @Schema(description = "技能名称", example = "code-review")
        var skillName: String? = null,

        @Schema(description = "技能描述", example = "代码审查技能")
        var skillDescription: String? = null
    )

    @Schema(description = "会话项")
    data class SessionItem(
        @Schema(description = "会话 ID", example = "1")
        var id: Long? = null,

        @Schema(description = "会话名称", example = "我的会话")
        var title: String? = null,

        @Schema(description = "会话描述", example = "这是一个会话")
        var sessionDescription: String? = null,

        @Schema(description = "会话 UUID", example = "session-123")
        var sessionId: String? = null
    )
}
