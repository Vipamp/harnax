package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The URL to send a user's browser to, and what was promised about it.
 *
 * The `state` is inside that URL rather than in this body: the browser carries it to the
 * authorization server and back, and the page that finishes the flow reads it off the returned
 * query. Repeating it here would only offer a second place to invent one - the value the exchange
 * accepts is the one this request actually sent.
 */
@Schema(description = "MCP OAuth authorization request")
data class McpOAuthAuthorizeResponse(
    @Schema(description = "Full authorization endpoint URL to navigate the browser to")
    val authorizeUrl: String,

    @Schema(description = "Issuer the user is being sent to")
    val issuer: String,

    @Schema(description = "Scopes actually requested; the authorization server may grant fewer")
    val scopes: List<String> = emptyList(),

    @Schema(description = "Seconds this request stays redeemable; after that, ask for a new URL")
    val expiresIn: Long,
)
