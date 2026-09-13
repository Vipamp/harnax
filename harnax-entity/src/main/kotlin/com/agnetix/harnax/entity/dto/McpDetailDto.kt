package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Full MCP server configuration returned by admin internal API.
 * Eliminates the need for agent-service to query mcp_server table directly.
 */
@Schema(description = "MCP server detail configuration")
data class McpDetailDto(
    @Schema(description = "MCP ID")
    val id: Long,

    @Schema(description = "MCP name")
    val name: String,

    @Schema(description = "MCP description")
    val description: String = "",

    @Schema(description = "MCP type (stdio/sse/streamablehttp)")
    val type: String = "streamablehttp",

    @Schema(description = "Execution command (only for stdio type)")
    val command: String = "",

    @Schema(description = "Service URL (for sse/streamablehttp type)")
    val url: String = "",

    /**
     * Upstream auth method, delivered so the runtime knows *how* to authenticate. Without it an OAuth
     * server is indistinguishable from a header one and a per-user token has nowhere to be injected.
     * Values: see `com.agnetix.harnax.entity.McpAuthTypes`.
     */
    @Schema(description = "Upstream auth method (NONE/STATIC_HEADER/OAUTH2)")
    val authType: String? = null,

    @Schema(description = "HTTP headers JSON string")
    val headers: String? = null,

    @Schema(description = "Environment parameters JSON string")
    val envParams: String? = null,

    @Schema(description = "Enable status (0:disabled, 1:enabled). Disabled servers are skipped when assembling the agent")
    val status: Int = 1,
)
