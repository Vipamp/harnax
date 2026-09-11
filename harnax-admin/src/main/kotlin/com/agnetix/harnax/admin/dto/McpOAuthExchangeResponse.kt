package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * What the user should be told after presenting an authorization code.
 *
 * [authorized] is false for every outcome where no grant was stored, and a refusal by the
 * authorization server is still an answer worth showing rather than an HTTP error: the browser
 * cannot retry a code it already spent, and the page has to say why. [message] is that sentence -
 * it may quote the authorization server, capped and with userinfo redacted, and it is rendered as
 * text by the front end.
 *
 * No field here can hold a token. The stored credential is ciphertext in one column and the point of
 * the flow is that the browser never sees the result of the exchange, so answering one would be a
 * leak with no consumer.
 */
@Schema(description = "MCP OAuth code exchange result")
data class McpOAuthExchangeResponse(
    @Schema(description = "A grant was stored for the calling user")
    val authorized: Boolean,

    @Schema(description = "One-line outcome for the user; on failure, what to do next")
    val message: String,

    @Schema(description = "Scopes actually granted, which may be narrower than what was requested")
    val scopes: List<String> = emptyList(),

    @Schema(description = "When the stored access token stops being usable")
    val accessExpiresAt: LocalDateTime? = null,
)
