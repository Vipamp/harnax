package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * OAuth client registration for one authorization server, shared by every MCP server of the same
 * tenant that talks to that issuer.
 */
@Schema(description = "MCP OAuth client registration")
class McpOauthClient : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "Registration ID")
    var id: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 1

    /**
     * Compared as an exact string everywhere: a token whose `iss` is not byte-equal to this value is
     * rejected rather than matched by prefix or host.
     */
    @Schema(description = "Authorization server issuer")
    var issuer: String = ""

    @Schema(description = "client_id")
    var clientId: String = ""

    @Schema(description = "AES ciphertext of the client secret; null for public (PKCE-only) clients")
    var clientSecretEnc: String? = null

    @Schema(description = "How the client was obtained: MANUAL/DCR/ID_METADATA")
    var registrationSource: String = "MANUAL"

    @Schema(description = "Discovery snapshot: authorization endpoint")
    var authorizationEndpoint: String? = null

    @Schema(description = "Discovery snapshot: token endpoint")
    var tokenEndpoint: String? = null

    @Schema(description = "Discovery snapshot: registration endpoint; null means no DCR support")
    var registrationEndpoint: String? = null

    @Schema(description = "Discovery snapshot: revocation endpoint; null means revoke locally only")
    var revocationEndpoint: String? = null

    @Schema(description = "Discovery snapshot: scopes_supported, comma-separated")
    var scopesSupported: String? = null

    @Schema(description = "Exact redirect_uri registered at the authorization server")
    var callbackUrl: String = ""

    @Schema(description = "Creator")
    var creator: String = ""

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
