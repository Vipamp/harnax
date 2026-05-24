package com.agnetix.harnax.agent.service.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Chat response DTO.
 * Contains the agent's response content.
 */
@Schema(description = "Chat response with agent reply")
data class ChatResponse(

    @Schema(description = "Agent response content")
    val content: String,
)
