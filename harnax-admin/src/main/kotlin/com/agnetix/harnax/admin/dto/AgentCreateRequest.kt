package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Agent creation request DTO
 */
@Schema(description = "Agent creation request object")
data class AgentCreateRequest(
    @Schema(description = "Agent name", example = "assistant", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Agent name cannot be empty")
    @Size(min = 1, max = 100, message = "Agent name length must be between 1-100")
    val name: String? = null,

    @Schema(description = "Agent description")
    val description: String? = null,

    @Schema(description = "System prompt (Markdown supported)")
    val systemPrompt: String? = null,

    @Schema(description = "Chat model ID", example = "1")
    val modelId: Long? = null,

    @Schema(description = "MCP server list")
    val mcpList: List<McpConfig>? = null,

    @Schema(description = "Skill ID list (comma separated)", example = "1,2,3")
    val skillList: String? = null,

    @Schema(description = "Tool configuration list")
    val toolList: List<ToolConfig>? = null,

    @Schema(description = "CLI configuration list")
    val cliList: List<CliConfig>? = null,

    @Schema(description = "Owner")
    val owner: String? = null,

    @Schema(description = "Status (0:disabled 1:enabled)", example = "1")
    val status: Int? = null,

    @Schema(description = "Is public (0:false 1:true)", example = "0")
    val isPublic: Int? = null,
) {
    /**
     * MCP configuration
     */
    @Schema(description = "MCP configuration")
    data class McpConfig(
        @Schema(description = "MCP ID", example = "1")
        val id: Long? = null,

        @Schema(description = "Environment variable bindings for MCP")
        val envBindings: List<EnvBinding>? = null,
    )

    /**
     * CLI configuration
     */
    @Schema(description = "CLI configuration")
    data class CliConfig(
        @Schema(description = "CLI ID", example = "1")
        val id: Long? = null,

        @Schema(description = "Environment variable bindings for CLI")
        val envBindings: List<EnvBinding>? = null,
    )

    /**
     * Skill configuration (deprecated, use skillList string field instead)
     */
    @Schema(description = "Skill configuration (deprecated)")
    data class SkillConfig(
        @Schema(description = "Repository ID", example = "1")
        val repositoryId: Long? = null,

        @Schema(description = "Repository name", example = "qoder-skills")
        val repositoryName: String? = null,

        @Schema(description = "Skill ID", example = "1")
        val skillId: Long? = null,

        @Schema(description = "Skill name", example = "code-review")
        val skillName: String? = null,
    )
}
