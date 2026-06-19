package com.agnetix.harnax.admin.dto.mp

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "Update mobile session request")
data class MpUpdateSessionRequest(
    @Schema(description = "New session name", example = "My Chat Session")
    @field:NotBlank(message = "Session name is required")
    val sessionName: String = "",
)
