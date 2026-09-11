package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * MCP server creation request DTO
 */
@Schema(description = "MCP server creation request object")
data class McpServerCreateRequest(
    @Schema(description = "MCP name", example = "my-mcp-server", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "MCP name cannot be empty")
    @Size(max = 100, message = "MCP name length cannot exceed 100 characters")
    val name: String? = null,

    @Schema(description = "MCP description", example = "This is an MCP server")
    val description: String? = null,

    @Schema(
        description = "MCP type (stdio/sse/streamablehttp)",
        example = "stdio",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    @NotBlank(message = "MCP type cannot be empty")
    val type: String = "streamablehttp",

    @Schema(
        description = "Execute command (only for stdio type)",
        example = "npx -y @modelcontextprotocol/server-filesystem /tmp",
    )
    val command: String? = null,

    @Schema(description = "Service URL (for sse/streamablehttp type)", example = "http://localhost:3000/sse")
    val url: String? = null,

    @Schema(
        description = "Upstream auth method (NONE/STATIC_HEADER/OAUTH2). Omitted means NONE",
        example = "NONE",
    )
    val authType: String? = null,

    @Schema(description = "OAuth configuration, only allowed when authType is OAUTH2")
    val oauthConfig: McpOAuthConfig? = null,

    @Schema(description = "Status (0:disabled, 1:enabled)", example = "1")
    val status: Int? = null,

    @Schema(description = "Whether public (0:no, 1:yes). Omitted means public", example = "1")
    val isPublic: Int? = null,

    @Schema(description = "HTTP headers configuration (for sse/streamablehttp type)")
    val headers: List<McpConfigEntry>? = null,

    @Schema(description = "Environment parameters configuration")
    val envParams: List<ToolEnvParamEntry>? = null,
)
