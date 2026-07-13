package com.agnetix.harnax.client.dto

/**
 * Chat request - sends a user message for the agent to process.
 *
 * @property sessionId Session identifier
 * @property message   User message content (text prompt)
 * @property imageUrls List of image URLs or base64 data URLs for multimodal input
 * @property requestId Optional request identifier for tracing
 */
data class ChatRequest(
    val sessionId: String,
    val message: String,
    val imageUrls: List<String> = emptyList(),
    val requestId: String = "",
    /**
     * Discriminator field matching the Router's polymorphic type (Jackson).
     * Must be "CHAT" for chat requests.
     */
    val type: String = "CHAT",
)
