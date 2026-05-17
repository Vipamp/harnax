package com.agnetix.harnax.admin.dto.response

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * User-tenant association response DTO
 */
@Schema(description = "User-tenant association response object")
data class UserTenantResponse(
    @Schema(description = "Association ID", example = "1")
    val id: Long? = null,

    @Schema(description = "User ID", example = "1")
    val userId: Long? = null,

    @Schema(description = "Username", example = "admin")
    val username: String? = null,

    @Schema(description = "Nickname", example = "Administrator")
    val nickname: String? = null,

    @Schema(description = "Tenant ID", example = "1")
    val tenantId: Long? = null,

    @Schema(description = "Tenant name", example = "Example Company")
    val tenantName: String? = null,

    @Schema(description = "Role (admin/member)", example = "admin")
    val role: String? = null,

    @Schema(description = "Status (0:disabled 1:enabled)", example = "1")
    val status: Int? = null,

    @Schema(description = "Join time", example = "2026-04-28T10:00:00")
    val joinedAt: LocalDateTime? = null,
)
