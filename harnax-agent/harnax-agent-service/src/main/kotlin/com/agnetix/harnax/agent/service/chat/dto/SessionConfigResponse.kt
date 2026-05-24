package com.agnetix.harnax.agent.service.chat.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Session configuration response DTO
 */
@Schema(description = "Session configuration response")
data class SessionConfigResponse(
    @Schema(description = "Session ID")
    val sessionId: String,

    @Schema(description = "Enable deep thinking")
    val enableThink: Boolean = false,

    @Schema(description = "Enable web search")
    val enableSearch: Boolean = false,

    @Schema(description = "Enable planning")
    val enablePlan: Boolean = false,
)
