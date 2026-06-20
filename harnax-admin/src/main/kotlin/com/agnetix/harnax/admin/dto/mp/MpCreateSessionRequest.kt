package com.agnetix.harnax.admin.dto.mp

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

@Schema(description = "Create mobile session request")
data class MpCreateSessionRequest(
    @Schema(description = "Session name", example = "New Chat")
    @field:NotBlank(message = "Session name is required")
    val sessionName: String = "",

    @Schema(description = "Agent ID to bind to this session", example = "1")
    @field:NotNull(message = "Agent ID is required")
    val agentId: Long? = null,
)
