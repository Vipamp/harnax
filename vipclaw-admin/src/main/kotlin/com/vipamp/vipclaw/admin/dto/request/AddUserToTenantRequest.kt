package com.vipamp.vipclaw.admin.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

/**
 * Add user to tenant request DTO
 */
@Schema(description = "Add user to tenant request object")
data class AddUserToTenantRequest(
    @Schema(description = "User ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @field:NotNull(message = "User ID cannot be empty")
    val userId: Long,

    @Schema(description = "Role (admin/member)", example = "member")
    @field:NotBlank(message = "Role cannot be empty")
    val role: String = "member",
)
