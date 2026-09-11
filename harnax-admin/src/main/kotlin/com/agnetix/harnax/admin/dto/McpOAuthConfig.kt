package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Non-sensitive OAuth configuration of one MCP server.
 *
 * Deliberately a typed DTO instead of a free-form JSON object: client credentials and tokens have no
 * field here, so they cannot be written into `mcp_server.oauth_config` by accident, which is the
 * column the UI reads back in plaintext.
 */
@Schema(description = "MCP OAuth non-sensitive configuration")
data class McpOAuthConfig(
    @Schema(
        description = "Authorization server issuer. Leave empty to discover it from the MCP server itself (RFC 9728)",
        example = "https://auth.example.com/realms/harnax",
    )
    val authorizationServer: String? = null,

    @Schema(description = "Scopes to request; keep this to the minimum the tool surface needs")
    val scopes: List<String> = emptyList(),

    @Schema(description = "Explicit audience parameter for authorization servers that require one")
    val audience: String? = null,

    @Schema(
        description = "Send the RFC 8707 resource parameter so the token is bound to this MCP server. " +
            "Turn it off only for authorization servers that reject unknown parameters",
    )
    val resourceIndicator: Boolean = true,
)
