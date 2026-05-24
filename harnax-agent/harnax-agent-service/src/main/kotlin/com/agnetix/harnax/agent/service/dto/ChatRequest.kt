package com.agnetix.harnax.agent.service.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Chat request DTO.
 * Contains the message content from the user.
 */
@Schema(description = "Chat request with user message")
data class ChatRequest(

    @Schema(description = "User message content", example = "Hello, how are you?")
    val message: String,
)
