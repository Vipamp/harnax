package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/**
 * 智能体更新请求 DTO
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Schema(description = "智能体更新请求对象")
data class AgentUpdateRequest(
    @Schema(description = "ID", example = "1")
    var id: Long? = null,

    @Schema(description = "智能体名称", example = "assistant")
    @Size(min = 1, max = 100, message = "智能体名称长度必须在 1-100 之间")
    val name: String? = null,

    @Schema(description = "智能体描述")
    val description: String? = null,

    @Schema(description = "系统提示词（支持 Markdown）")
    val systemPrompt: String? = null,

    @Schema(description = "对话模型 ID", example = "1")
    val modelId: Long? = null,

    @Schema(description = "MCP 服务列表")
    val mcpList: List<AgentCreateRequest.McpConfig>? = null,

    @Schema(description = "技能 ID 列表（逗号分隔）", example = "1,2,3")
    val skillList: String? = null,

    @Schema(description = "所有者")
    val owner: String? = null,

    @Schema(description = "状态 (0:禁用 1:正常)", example = "1")
    val status: Int? = null,

    @Schema(description = "是否公开 (0:否 1:是)", example = "1")
    val isPublic: Int? = null,
)
