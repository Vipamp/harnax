package com.agnetix.harnax.admin.entity

import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

@Schema(description = "User-tenant association entity")
class UserTenantEntity : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    @Schema(description = "ID")
    var id: Long = 0

    @Schema(description = "User ID")
    var userId: Long = 0

    @Schema(description = "Tenant ID")
    var tenantId: Long = 0

    @Schema(description = "Role (admin/member)")
    var role: String = "member"

    @Schema(description = "Status (0:disabled, 1:enabled)")
    var status: Int = 1

    @Schema(description = "Join time")
    var joinedAt: LocalDateTime? = null
}
