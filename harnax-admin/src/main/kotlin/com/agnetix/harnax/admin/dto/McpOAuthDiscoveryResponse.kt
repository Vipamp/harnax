package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * What one discovery run found, and what was written to `mcp_oauth_client` because of it.
 *
 * No client secret here: the value is only ever echoed masked, same convention as `headers`.
 */
@Schema(description = "MCP OAuth discovery result")
data class McpOAuthDiscoveryResponse(
    @Schema(description = "Authorization server issuer, stored as an exact string")
    val issuer: String? = null,

    @Schema(
        description = "How the issuer was located: CONFIG (oauth_config.authorizationServer), " +
            "PROTECTED_RESOURCE (RFC 9728 metadata) or RESOURCE_METADATA (401 WWW-Authenticate)",
    )
    val issuerSource: String? = null,

    @Schema(description = "Authorization endpoint advertised by the authorization server")
    val authorizationEndpoint: String? = null,
    @Schema(description = "Token endpoint advertised by the authorization server")
    val tokenEndpoint: String? = null,
    @Schema(description = "Dynamic registration endpoint; null when the server has none")
    val registrationEndpoint: String? = null,
    @Schema(description = "Token revocation endpoint; null means revoke locally only")
    val revocationEndpoint: String? = null,

    @Schema(description = "Scopes the authorization server says it can grant")
    val scopesSupported: List<String> = emptyList(),

    @Schema(description = "Scopes this MCP server asks for that the authorization server did not list")
    val unknownScopes: List<String> = emptyList(),

    @Schema(description = "client_id on file after this run; null until a client is registered")
    val clientId: String? = null,

    @Schema(description = "Whether a client secret is stored (never its value)")
    val clientSecretPresent: Boolean = false,

    @Schema(description = "redirect_uri that must be whitelisted at the authorization server")
    val callbackUrl: String? = null,

    @Schema(
        description = "redirect_uri this deployment builds today. Differs from callbackUrl when the " +
            "stored registration predates a change of the deployment's address; the authorization " +
            "server compares the two as exact strings",
    )
    val defaultCallbackUrl: String? = null,
)
