package com.vipamp.vipclaw.admin.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * Session creation request object
 *
 * @author vipamp
 * @since 2026-03-25
 */
@Schema(description = "Session creation request object")
data class SessionCreateRequest(
    @Schema(description = "Session name", example = "My Session", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Session name cannot be empty")
    @Size(max = 100, message = "Session name length cannot exceed 100 characters")
    val title: String = "",

    @Schema(description = "Session description", example = "This is a session description")
    val sessionDescription: String = "",

    @Schema(description = "Associated agent ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Agent ID cannot be empty")
    val agentId: Long = 0,
)
