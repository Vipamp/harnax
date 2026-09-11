package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Outcome of a revoke: this deployment's copy is always cleared, the upstream one may not be.
 *
 * The split matters out loud. A user who is told "revoked" while the authorization server still
 * accepts the token would act on a false belief, so [message] says which of the two happened rather
 * than reporting success for both.
 */
@Schema(description = "MCP OAuth revoke result")
data class McpOAuthRevokeResponse(
    @Schema(description = "The stored credential was cleared and the row set to REVOKED")
    val revoked: Boolean,

    @Schema(description = "The authorization server answered a revocation request (RFC 7009)")
    val upstreamRevoked: Boolean,

    @Schema(description = "What happened, including why an upstream revocation did not happen")
    val message: String,
)
