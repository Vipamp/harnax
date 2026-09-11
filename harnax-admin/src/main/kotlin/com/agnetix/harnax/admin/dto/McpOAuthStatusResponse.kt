package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * What one user's grant on one MCP server looks like right now.
 *
 * Tokens are absent by construction: the row holds ciphertexts and this DTO has no field for them,
 * so a controller cannot leak one by serializing the wrong object.
 */
@Schema(description = "MCP OAuth authorization status of the current user")
data class McpOAuthStatusResponse(
    @Schema(
        description = "A usable grant exists. With token refresh not wired yet (design P2-4) an " +
            "expired access token reads as not authorized, which is what it is today",
    )
    val authorized: Boolean,

    @Schema(description = "ACTIVE / NEEDS_CONSENT / REVOKED, or null when this user never authorized")
    val status: String? = null,

    @Schema(description = "Scopes actually granted, which may be narrower than what was requested")
    val scopes: List<String> = emptyList(),

    @Schema(description = "When the stored access token stops being usable")
    val accessExpiresAt: LocalDateTime? = null,

    @Schema(description = "When a token was last obtained, by authorization code or refresh")
    val lastRefreshedAt: LocalDateTime? = null,

    @Schema(description = "Last failure the flow recorded, redacted of any token material")
    val lastError: String? = null,
)
