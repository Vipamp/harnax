package com.vipamp.vipclaw.ascopagent.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Chat Request DTO
 */
@Schema(description = "Chat Request")
data class ChatRequest(
    @Schema(description = "Session ID")
    val sessionId: String,

    @Schema(description = "Message content")
    val message: String,

    @Schema(description = "Image URL list")
    val imageUrl: List<String> = emptyList(),

    @Schema(description = "Enable thinking mode")
    val enableThink: Boolean = false,

    @Schema(description = "Enable search")
    val enableSearch: Boolean = false,

    @Schema(description = "Enable planning")
    val enablePlan: Boolean = false,
)
