package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.List

/**
 * 会话响应对象
 */
@Schema(description = "会话响应对象")
data class SessionResponse(
    @Schema(description = "ID", example = "1")
    val id: Long? = null,
    @Schema(description = "会话名称", example = "我的会话")
    val title: String? = null,
    @Schema(description = "会话描述", example = "这是一个会话描述")
    val sessionDescription: String? = null,
    @Schema(description = "会话ID", example = "session-123")
    val sessionId: String? = null,
    @Schema(description = "关联的智能体ID", example = "1")
    val agentId: Long? = null,
    @Schema(description = "智能体名称", example = "assistant")
    val name: String? = null,
    @Schema(description = "智能体描述")
    val description: String? = null,
    @Schema(description = "系统提示词（支持 Markdown）")
    val systemPrompt: String? = null,
    @Schema(description = "对话模型 ID", example = "1")
    val modelId: Long? = null,
    @Schema(description = "对话模型名称", example = "GPT-4")
    val modelName: String? = null,
    @Schema(description = "模型价格（元/百万token）", example = "15.0")
    val modelPrice: Double? = null,
    @Schema(description = "MCP 服务列表")
    val mcpList: List<AgentResponse.McpItem>? = null,
    @Schema(description = "技能列表")
    val skillList: List<AgentResponse.SkillItem>? = null,
    @Schema(description = "所有者", example = "admin")
    val owner: String? = null,
    @Schema(description = "是否启用（0:禁用，1:启用）", example = "1")
    val status: Int? = null,
    @Schema(description = "是否公开（0:否，1:是）", example = "1")
    val isPublic: Int? = null,
    @Schema(description = "创建人", example = "admin")
    val creator: String? = null,
    @Schema(description = "创建时间", example = "2026-03-25 12:00:00")
    val createTime: LocalDateTime? = null,
    @Schema(description = "更新时间", example = "2026-03-25 12:00:00")
    val updateTime: LocalDateTime? = null,
    @Schema(description = "MCP ID", example = "1")
    val mcpId: Long? = null,
    @Schema(description = "MCP 名称", example = "filesystem")
    val mcpName: String? = null,
    @Schema(description = "MCP 描述", example = "文件系统服务")
    val mcpDescription: String? = null,
    @Schema(description = "是否允许跳过", example = "true")
    val enableSkip: String? = null,
    @Schema(description = "仓库 ID", example = "1")
    val repositoryId: Long? = null,
    @Schema(description = "仓库名称", example = "qoder-skills")
    val repositoryName: String? = null,
    @Schema(description = "技能 ID", example = "1")
    val skillId: Long? = null,
    @Schema(description = "技能名称", example = "code-review")
    val skillName: String? = null
)
