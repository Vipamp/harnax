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

    @Schema(description = "MCP name. Omitted means unchanged", example = "my-mcp-server")
    @Size(max = 100, message = "MCP name length cannot exceed 100 characters")
    val name: String? = null,

    @Schema(description = "MCP description. Omitted means unchanged", example = "This is an MCP server")
    val description: String? = null,

    @Schema(description = "MCP type (stdio/sse/streamablehttp). Omitted means unchanged", example = "stdio")
    val type: String? = null,

    @Schema(
        description = "Execute command (only for stdio type). Omitted means unchanged",
        example = "npx -y @modelcontextprotocol/server-filesystem /tmp",
    )
    val command: String? = null,

    @Schema(
        description = "Service URL (for sse/streamablehttp type). Omitted means unchanged",
        example = "http://localhost:3000/sse",
    )
    val url: String? = null,

    @Schema(
        description = "Upstream auth method (NONE/STATIC_HEADER/OAUTH2). Omitted means unchanged",
        example = "OAUTH2",
    )
    val authType: String? = null,

    @Schema(
        description = "OAuth configuration, only allowed when authType is OAUTH2. A provided value replaces the stored " +
            "config, an omitted one leaves it as is, and moving authType away from OAUTH2 clears it",
    )
    val oauthConfig: McpOAuthConfig? = null,

    @Schema(description = "Status (0:disabled, 1:enabled). Omitted means unchanged", example = "1")
    val status: Int? = null,

    @Schema(description = "Whether public (0:no, 1:yes). Omitted means unchanged", example = "1")
    val isPublic: Int? = null,

    @Schema(description = "HTTP headers configuration (for sse/streamablehttp type)")
    val headers: List<McpConfigEntry>? = null,

    @Schema(description = "Environment parameters configuration")
    val envParams: List<ToolEnvParamEntry>? = null,
)
