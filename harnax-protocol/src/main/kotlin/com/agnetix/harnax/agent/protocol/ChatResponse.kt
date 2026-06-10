package com.agnetix.harnax.agent.protocol

/**
 * ChatResponse - direct (non-streaming) chat response.
 *
 * Used when the caller prefers a single aggregated response
 * instead of Flux<ChatEvent> streaming.
 *
 * @property sessionId Session identifier
 * @property content   Aggregated text content from the agent
 * @property thinking  Aggregated thinking content (if available)
 * @property tokenUsage Token usage statistics (if available)
 */
data class ChatResponse(
    val sessionId: String,
    val content: String,
    val thinking: String? = null,
    val tokenUsage: TokenUsage? = null,
) {

    companion object {
        /**
         * Build a ChatResponse by collecting all events from a Flux<ChatEvent> stream.
         * Extracts text and thinking content, and captures the last token usage.
         */
        @JvmStatic
        fun fromEvents(sessionId: String, events: List<ChatEvent>): ChatResponse {
            val textParts = mutableListOf<String>()
            val thinkingParts = mutableListOf<String>()
            var lastTokenUsage: TokenUsage? = null

            for (event in events) {
                when (event) {
                    is StreamTextChatEvent -> textParts.add(event.message)
                    is StreamThinkingChatEvent -> thinkingParts.add(event.message)
                    else -> {} // tool events and end event are ignored
                }
                if (event.tokenUsage != null) {
                    lastTokenUsage = event.tokenUsage
                }
            }

            return ChatResponse(
                sessionId = sessionId,
                content = textParts.joinToString(""),
                thinking = thinkingParts.joinToString("").ifEmpty { null },
                tokenUsage = lastTokenUsage,
            )
        }
    }
}
