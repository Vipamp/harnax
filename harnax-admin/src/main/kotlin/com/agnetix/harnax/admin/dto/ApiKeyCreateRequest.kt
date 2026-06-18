package com.agnetix.harnax.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "API Key creation request object")
data class ApiKeyCreateRequest(
    @Schema(description = "API Key name", example = "third-party-xxx", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Name cannot be empty")
    @Size(max = 128, message = "Name length cannot exceed 128 characters")
    val name: String? = null,

    @Schema(description = "Comma-separated scopes (e.g. api:chat,api:session)", example = "api:chat,api:session", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Scopes cannot be empty")
    val scopes: String? = null,

    @Schema(description = "Tenant ID")
    val tenantId: Long? = null,

    @Schema(description = "Rate limit per minute", example = "60")
    val rateLimit: Int? = null,

    @Schema(description = "Expiration time (ISO format)", example = "2026-12-31T23:59:59")
    val expiresAt: String? = null,
)
