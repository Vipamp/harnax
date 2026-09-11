package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Manual (pre-registered) client credentials for one authorization server.
 *
 * Dynamic registration is not part of this request: it is P4, and an operator who can paste a
 * client_id can paste the one their AS already issued them.
 */
@Schema(description = "MCP OAuth client registration request")
data class McpOAuthClientRequest(
    @Schema(description = "client_id issued by the authorization server", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "client_id cannot be empty")
    @Size(max = 255, message = "client_id length cannot exceed 255 characters")
    val clientId: String? = null,

    @Schema(
        description = "client_secret. Omitted or masked keeps what is stored; an empty string clears " +
            "it, which makes this a public client relying on PKCE alone",
    )
    val clientSecret: String? = null,

    @Schema(
        description = "redirect_uri registered at the authorization server. Omitted to use this " +
            "deployment's own callback address",
    )
    @Size(max = 500, message = "callbackUrl length cannot exceed 500 characters")
    val callbackUrl: String? = null,
)
