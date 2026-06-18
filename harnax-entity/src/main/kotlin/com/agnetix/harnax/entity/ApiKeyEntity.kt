package com.agnetix.harnax.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "API Key entity")
class ApiKeyEntity : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "API Key name")
    var name: String = ""

    @Schema(description = "SHA-256 hash of the raw key")
    var keyHash: String = ""

    @Schema(description = "Key prefix for display (e.g. hnx_sk_live_...xxxx)")
    var keyPrefix: String = ""

    @Schema(description = "Comma-separated scopes (e.g. api:chat,api:session)")
    var scopes: String = ""

    @Schema(description = "Tenant ID")
    var tenantId: Long? = null

    @Schema(description = "Rate limit per minute")
    var rateLimit: Int = 60

    @Schema(description = "Whether enabled (0:disabled, 1:enabled)")
    var enabled: Int = 1

    @Schema(description = "Expiration time")
    var expiresAt: LocalDateTime? = null

    @Schema(description = "Creator")
    var creator: String = "system"

    @Schema(description = "Active status (0:deleted, 1:active)")
    var active: Int = 1

    @Schema(description = "Creation time")
    var createTime: LocalDateTime = LocalDateTime.now()

    @Schema(description = "Update time")
    var updateTime: LocalDateTime = LocalDateTime.now()
}
