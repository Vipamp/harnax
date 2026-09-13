package com.agnetix.harnax.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * One access token, minted for the owner of a runtime session so the agent can call an OAuth MCP
 * server as that user.
 *
 * Lives here rather than in admin's own dto package because it is a wire contract between two
 * services: admin mints it on `POST /api/admin/internal/mcp/access-token`, agent-service reads it
 * back. Same reason [AgentSpecInfoResponse] sits in this package.
 *
 * Unlike admin's `McpOAuthStatusResponse`, this one does carry the secret, on purpose: it answers the
 * internal API (design section 7.2), which only agent-service reaches and which
 * `InternalApiAuthFilter` keeps off the public paths. It is never returned from a `/api/admin/mcp`
 * endpoint, and no field of it goes into a log line.
 */
@Schema(description = "MCP access token for one runtime session")
data class McpAccessTokenResponse(
    @Schema(description = "Bearer token to present to the MCP server")
    val accessToken: String,

    @Schema(description = "Always Bearer today; kept so a future mTLS/mAC answer has somewhere to go")
    val tokenType: String = "Bearer",

    @Schema(
        description = "Unix epoch second at which this token stops being usable, or null when the " +
            "authorization server never said - the caller then treats it as valid until it is refused. " +
            "An epoch rather than a date-time because each service compares it against its own clock " +
            "and the two may not be in the same time zone",
        nullable = true,
    )
    val expiresAtEpochSecond: Long? = null,
)
