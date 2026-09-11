package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * The OAuth grant one user obtained for one MCP server. Ciphertexts stay inside the admin process:
 * no response DTO ever carries them, and the runtime only ever sees an access token it asked for.
 */
@Schema(description = "MCP per-user OAuth credential")
class McpUserCredential : Serializable {

    companion object {
        private const val serialVersionUID = 1L

        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_NEEDS_CONSENT = "NEEDS_CONSENT"
        const val STATUS_REVOKED = "REVOKED"
    }

    @Schema(description = "Credential ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    /**
     * `sys_user.id`, never a username: a rename must not orphan or transfer a grant.
     */
    @Schema(description = "FK to sys_user.id")
    var userId: Long = 0

    @Schema(description = "FK to mcp_server.id")
    var mcpId: Long = 0

    @Schema(description = "AES ciphertext of the access token; cleared on revoke")
    var accessTokenEnc: String? = null

    @Schema(description = "AES ciphertext of the refresh token; never leaves the admin process")
    var refreshTokenEnc: String? = null

    @Schema(description = "Access token expiry; past due is treated as missing")
    var accessExpiresAt: LocalDateTime? = null

    @Schema(description = "Scopes actually granted, may be narrower than requested")
    var scopes: String? = null

    @Schema(description = "ACTIVE / NEEDS_CONSENT / REVOKED")
    var status: String = STATUS_ACTIVE

    @Schema(description = "Redacted failure reason; never contains a token fragment")
    var lastError: String? = null

    @Schema(description = "Last successful refresh")
    var lastRefreshedAt: LocalDateTime? = null

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
