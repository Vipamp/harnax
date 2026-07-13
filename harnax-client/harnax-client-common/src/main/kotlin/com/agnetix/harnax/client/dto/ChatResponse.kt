package com.agnetix.harnax.client.dto

/**
 * Chat response - aggregated (non-streaming) response from the agent.
 *
 * @property sessionId  Session identifier
 * @property content    Aggregated text content from the agent
 * @property thinking   Aggregated thinking content (if available)
 * @property tokenUsage Token usage statistics (if available)
 */
data class ChatResponse(
    val sessionId: String,
    val content: String,
    val thinking: String? = null,
    val tokenUsage: TokenUsage? = null,
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
