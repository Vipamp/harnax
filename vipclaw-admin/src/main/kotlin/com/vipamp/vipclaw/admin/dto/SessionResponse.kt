package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * Session response object
 */
@Schema(description = "Session response object")
data class SessionResponse(
    @Schema(description = "ID", example = "1")
    var id: Long? = null,
    @Schema(description = "Session name", example = "我的会话")
    var title: String? = null,
    @Schema(description = "Session description", example = "这是一个Session description")
    var sessionDescription: String? = null,
    @Schema(description = "会话ID", example = "session-123")
    var sessionId: String? = null,
    @Schema(description = "Associated agent ID", example = "1")
    var agentId: Long? = null,
    @Schema(description = "Agent name", example = "assistant")
    var name: String? = null,
    @Schema(description = "智能体描述")
    var description: String? = null,
    @Schema(description = "系统提示词（支持 Markdown）")
    var systemPrompt: String? = null,
    @Schema(description = "对话模型 ID", example = "1")
    var modelId: Long? = null,
    @Schema(description = "对话模型名称", example = "GPT-4")
    var modelName: String? = null,
    @Schema(description = "模型价格（元/百万token）", example = "15.0")
    var modelPrice: Double? = null,
    @Schema(description = "是否启用深度思考（0:否，1:是）", example = "0")
    var enableThink: Int? = null,
    @Schema(description = "是否启用联网搜索（0:否，1:是）", example = "0")
    var enableSearch: Int? = null,
    @Schema(description = "是否启用计划（0:否，1:是）", example = "0")
    var enablePlan: Int? = null,
    @Schema(description = "MCP 服务列表")
    var mcpList: List<McpItem> = listOf(),
    @Schema(description = "技能列表")
    var skillList: List<SkillItem> = emptyList(),
    @Schema(description = "所有者", example = "admin")
    var owner: String? = null,
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    var status: Int? = null,
    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    var isPublic: Int? = null,
    @Schema(description = "创建人", example = "admin")
    var creator: String? = null,
    @Schema(description = "Creation time", example = "2026-03-25 12:00:00")
    var createTime: LocalDateTime? = null,
    @Schema(description = "Update time", example = "2026-03-25 12:00:00")
    var updateTime: LocalDateTime? = null,
) {
    @Schema(description = "MCP 项")
    data class McpItem(
        @Schema(description = "MCP ID", example = "1")
        var mcpId: Long? = null,
        @Schema(description = "MCP 名称", example = "filesystem")
        var mcpName: String? = null,
        @Schema(description = "MCP 描述", example = "文件系统服务")
        var mcpDescription: String? = null,
        @Schema(description = "是否允许跳过", example = "true")
        var enableSkip: String? = null,
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
        var skillDescription: String? = null,
    )
}
