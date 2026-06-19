package com.agnetix.harnax.admin.dto.mp

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Mobile chat message DTO")
data class MpChatMessageDto(
    @Schema(description = "Message role (user / assistant / system)")
    val role: String = "user",

    @Schema(description = "Plain text content")
    val content: String = "",

    @Schema(description = "Message segments (JSON array)")
    val segmentsJson: String = "[]",

    @Schema(description = "Token usage info (JSON object)")
    val tokenUsageJson: String? = null,

    @Schema(description = "Image URLs (JSON array)")
    val imageUrlsJson: String? = null,
)
