package com.agnetix.harnax.admin.dto

import com.agnetix.harnax.entity.ApiKeyEntity
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "API Key response object")
data class ApiKeyResponse(
    @Schema(description = "ID")
    val id: Long? = null,

    @Schema(description = "API Key name")
    val name: String? = null,

    @Schema(description = "Key prefix for display")
    val keyPrefix: String? = null,

    @Schema(description = "Comma-separated scopes")
    val scopes: String? = null,

    @Schema(description = "Tenant ID")
    val tenantId: Long? = null,

    @Schema(description = "Rate limit per minute")
    val rateLimit: Int? = null,

    @Schema(description = "Whether enabled (0:disabled, 1:enabled)")
    val enabled: Int? = null,

    @Schema(description = "Expiration time")
    val expiresAt: LocalDateTime? = null,

    @Schema(description = "Creator")
    val creator: String? = null,

    @Schema(description = "Creation time")
    val createTime: LocalDateTime? = null,

    @Schema(description = "Update time")
    val updateTime: LocalDateTime? = null,
) {
    companion object {
        fun fromEntity(entity: ApiKeyEntity): ApiKeyResponse = ApiKeyResponse(
            id = entity.id,
            name = entity.name,
            keyPrefix = entity.keyPrefix,
            scopes = entity.scopes,
            tenantId = entity.tenantId,
            rateLimit = entity.rateLimit,
            enabled = entity.enabled,
            expiresAt = entity.expiresAt,
            creator = entity.creator,
            createTime = entity.createTime,
            updateTime = entity.updateTime,
        )
    }
}

@Schema(description = "API Key creation response (contains the raw key, shown only once)")
data class ApiKeyCreatedResponse(
    @Schema(description = "ID")
    val id: Long,

    @Schema(description = "API Key name")
    val name: String,

    @Schema(description = "Raw API Key (save this, it will not be shown again)")
    val rawKey: String,

    @Schema(description = "Key prefix for display")
    val keyPrefix: String,
)
