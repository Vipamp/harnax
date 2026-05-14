package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Agent creation request DTO
 *
 * @author vipamp
 * @since 2026-03-18
 */
@Schema(description = "Agent creation request object")
data class AgentCreateRequest(
    @Schema(description = "Agent name", example = "assistant", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Agent name不能为空")
    @Size(min = 1, max = 100, message = "Agent name长度必须在 1-100 之间")
    val name: String? = null,

    @Schema(description = "Agent description")
    val description: String? = null,

    @Schema(description = "System prompt (Markdown supported)")
    val systemPrompt: String? = null,

    @Schema(description = "Chat model ID", example = "1")
    val modelId: Long? = null,

    @Schema(description = "MCP 服务列表")
    val mcpList: List<McpConfig>? = null,

    @Schema(description = "技能 ID 列表（逗号分隔）", example = "1,2,3")
    val skillList: String? = null,

    @Schema(description = "所有者")
    val owner: String? = null,

    @Schema(description = "状态 (0:禁用 1:正常)", example = "1")
    val status: Int? = null,
) {
    /**
     * MCP 配置
     */
    @Schema(description = "MCP 配置")
    data class McpConfig(
        @Schema(description = "MCP ID", example = "1")
        val id: Long? = null,

        @Schema(description = "是否允许跳过", example = "true")
        val enableSkip: String? = null,
    )

    /**
     * 技能配置（已废弃，请使用 skillList 字符串字段）
     */
    @Schema(description = "技能配置（已废弃）")
    data class SkillConfig(
        @Schema(description = "仓库 ID", example = "1")
        val repositoryId: Long? = null,

        @Schema(description = "仓库名称", example = "qoder-skills")
        val repositoryName: String? = null,

        @Schema(description = "技能 ID", example = "1")
        val skillId: Long? = null,

        @Schema(description = "技能名称", example = "code-review")
        val skillName: String? = null,
    )
}
