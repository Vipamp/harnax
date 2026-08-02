package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/**
 * Agent update request DTO
 */
@Schema(description = "Agent update request object")
data class AgentUpdateRequest(
    @Schema(description = "ID", example = "1")
    var id: Long? = null,

    @Schema(description = "Agent name", example = "assistant")
    @Size(min = 1, max = 100, message = "Agent name length must be between 1-100")
    val name: String? = null,

    @Schema(description = "Agent description")
    val description: String? = null,

    @Schema(description = "System prompt (Markdown supported)")
    val systemPrompt: String? = null,

    @Schema(description = "Chat model ID", example = "1")
    val modelId: Long? = null,

    @Schema(description = "MCP service list")
    val mcpList: List<AgentCreateRequest.McpConfig>? = null,

    @Schema(description = "Skill ID list (comma separated)", example = "1,2,3")
    val skillList: String? = null,

    @Schema(description = "Tool configuration list")
    val toolList: List<ToolConfig>? = null,

    @Schema(description = "CLI configuration list")
    val cliList: List<AgentCreateRequest.CliConfig>? = null,

    @Schema(description = "Owner")
    val owner: String? = null,

    @Schema(description = "Status (0:disabled 1:enabled)", example = "1")
    val status: Int? = null,

    @Schema(description = "Is public (0:false 1:true)", example = "1")
    val isPublic: Int? = null,
)
