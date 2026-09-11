package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

/**
 * What the authorization server sent the browser back with, handed to the exchange endpoint by the
 * page that received it.
 *
 * All four fields are optional because the AS picks which to send: a consent that went through
 * carries `code` and `state`, one that was refused carries `error` and often `error_description`,
 * and a `state` that never matched an outstanding request can arrive with anything at all. Nothing
 * here says *which* MCP server is being authorized - that comes from the pending request the `state`
 * names, so a caller cannot point a captured `state` at a server of their choosing.
 *
 * The sizes are a bound on the request body, not a trust check: an over-long `code` is not a code,
 * and this value is forwarded to the authorization server.
 */
@Schema(description = "MCP OAuth code exchange request")
data class McpOAuthExchangeRequest(
    @Schema(description = "The code the authorization server returned")
    @Size(max = 4096, message = "code length cannot exceed 4096 characters")
    val code: String? = null,

    @Schema(description = "The one-time state this flow started with")
    @Size(max = 512, message = "state length cannot exceed 512 characters")
    val state: String? = null,

    @Schema(description = "error, when the authorization server refused the request")
    @Size(max = 128, message = "error length cannot exceed 128 characters")
    val error: String? = null,

    @Schema(description = "error_description, quoted back to the user with the upstream's own words")
    @Size(max = 1024, message = "errorDescription length cannot exceed 1024 characters")
    val errorDescription: String? = null,
)
