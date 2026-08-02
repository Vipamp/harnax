package com.agnetix.harnax.client.dto

/**
 * Chat response - aggregated (non-streaming) response from the agent.
 *
 * @property sessionId    Session identifier
 * @property content      Aggregated text content from the agent (includes download links)
 * @property thinking     Aggregated thinking content (if available)
 * @property tokenUsage   Token usage statistics (if available)
 * @property attachments  File attachments produced during execution (for channel delivery)
 */
data class ChatResponse(
    val sessionId: String,
    val content: String,
    val thinking: String? = null,
    val tokenUsage: TokenUsage? = null,
    val attachments: List<FileAttachment> = emptyList(),
)

/**
 * Token usage statistics for a single agent interaction.
 */
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val costTime: Double,
    val timestamp: Long,
)
