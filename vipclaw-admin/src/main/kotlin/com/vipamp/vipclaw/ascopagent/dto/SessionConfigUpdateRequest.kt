package com.vipamp.vipclaw.ascopagent.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Session configuration update request DTO
 */
@Schema(description = "Session configuration update request")
data class SessionConfigUpdateRequest(
    @Schema(description = "Enable deep thinking")
    val enableThink: Boolean = false,

    @Schema(description = "Enable web search")
    val enableSearch: Boolean = false,

    @Schema(description = "Enable planning")
    val enablePlan: Boolean = false,
)
