package com.agnetix.harnax.admin.dto.mp

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Mobile agent detail response")
data class MpAgentDetailResponse(
    @Schema(description = "Agent ID")
    val id: Long,

    @Schema(description = "Agent name")
    val name: String,

    @Schema(description = "Agent description")
    val description: String,

    @Schema(description = "Model name")
    val modelName: String,

    @Schema(description = "Model provider")
    val modelProvider: String,

    @Schema(description = "MCP tool list")
    val mcpList: List<McpInfo>,

    @Schema(description = "Skill list")
    val skillList: List<SkillInfo>,

    @Schema(description = "Deep thinking enabled")
    val enableThink: Boolean,

    @Schema(description = "Internet search enabled")
    val enableSearch: Boolean,

    @Schema(description = "Planning enabled")
    val enablePlan: Boolean,
) {
    data class McpInfo(
        val id: Long,
        val name: String,
        val description: String,
    )

    data class SkillInfo(
        val id: Long,
        val name: String,
        val description: String,
    )
}
