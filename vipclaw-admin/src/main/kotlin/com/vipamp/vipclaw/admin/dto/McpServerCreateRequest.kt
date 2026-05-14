package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * MCP server creation request DTO
 */
@Schema(description = "MCP server creation request object")
data class McpServerCreateRequest(
    @Schema(description = "MCP name", example = "my-mcp-server", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "MCP name不能为空")
    @Size(max = 100, message = "MCP name长度不能超过 100 个字符")
    val name: String? = null,

    @Schema(description = "MCP description", example = "This is an MCP server")
    val description: String? = null,

    @Schema(
        description = "MCP type (stdio/sse/streamablehttp)",
        example = "stdio",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @NotBlank(message = "MCP type cannot be empty")
    val type: String = "stdio",

    @Schema(
        description = "Execute command (only for stdio type)",
        example = "npx -y @modelcontextprotocol/server-filesystem /tmp",
    )
    val command: String? = null,

    @Schema(description = "Service URL (for sse/streamablehttp type)", example = "http://localhost:3000/sse")
    val url: String? = null,

    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    val status: Int? = null,
)
