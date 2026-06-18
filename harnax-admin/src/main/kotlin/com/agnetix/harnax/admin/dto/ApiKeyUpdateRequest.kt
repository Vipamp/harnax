package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "API Key update request object")
data class ApiKeyUpdateRequest(
    @Schema(description = "Comma-separated scopes (e.g. api:chat,api:session)")
    val scopes: String? = null,

    @Schema(description = "Tenant ID")
    val tenantId: Long? = null,

    @Schema(description = "Rate limit per minute")
    val rateLimit: Int? = null,

    @Schema(description = "Whether enabled (0:disabled, 1:enabled)")
    val enabled: Int? = null,

    @Schema(description = "Expiration time (ISO format)")
    val expiresAt: String? = null,
)
