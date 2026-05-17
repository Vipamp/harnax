package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/**
 * MCP server update request DTO
 */
@Schema(description = "MCP server update request object")
data class McpServerUpdateRequest(
    @Schema(description = "MCP ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val id: Long = 0,

    @Schema(description = "MCP name", example = "my-mcp-server")
    @Size(max = 100, message = "MCP name length cannot exceed 100 characters")
    val name: String = "",

    @Schema(description = "MCP description", example = "This is an MCP server")
    val description: String = "",

    @Schema(description = "MCP type (stdio/sse/streamablehttp)", example = "stdio")
    val type: String = "streamablehttp",

    @Schema(
        description = "Execute command (only for stdio type)",
        example = "npx -y @modelcontextprotocol/server-filesystem /tmp",
    )
    val command: String = "",

    @Schema(description = "Service URL (for sse/streamablehttp type)", example = "http://localhost:3000/sse")
    val url: String = "",

    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    val status: Int = 0,

    @Schema(description = "Whether public (0:no, 1:yes)", example = "1")
    val isPublic: Int = 0,
)
