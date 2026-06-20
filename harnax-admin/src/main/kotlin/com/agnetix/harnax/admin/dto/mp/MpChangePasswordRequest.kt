package com.agnetix.harnax.admin.dto.mp

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "Change password request")
data class MpChangePasswordRequest(
    @Schema(description = "Current password (SHA-256 hashed)")
    @field:NotBlank(message = "Old password is required")
    val oldPassword: String = "",

    @Schema(description = "New password (SHA-256 hashed)")
    @field:NotBlank(message = "New password is required")
    val newPassword: String = "",
)
