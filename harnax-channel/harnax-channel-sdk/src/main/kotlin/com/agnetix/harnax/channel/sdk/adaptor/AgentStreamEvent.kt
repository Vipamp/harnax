package com.agnetix.harnax.channel.sdk.adaptor

/**
 * Agent Stream Event Types
 *
 * Channel-oriented streaming event types for real-time and batch message output.
 * More concise than ChatEvent, containing only what channels need:
 * - Text streaming fragments
 * - Thinking status indicator
 * - Stream completion with optional merged content
 * - Error events for graceful degradation
 *
 * Uses Kotlin Flow instead of Reactor Flux to keep SDK lightweight (no reactor dependency).
 */
sealed class AgentStreamEvent {

    /**
     * Text Stream Event
     *
     * Represents a fragment of AI-generated text output.
     * For streaming channels: send each fragment immediately.
     * For batch channels: buffer fragments, merge into complete message on EndStreamEvent.
     *
     * @param content Text fragment content
     * @param isLast Whether this is the last text fragment in current stream
     */
    data class TextStreamEvent(
        val content: String,
        val isLast: Boolean,
    ) : AgentStreamEvent()

    /**
     * Thinking Stream Event
     *
     * Represents AI thinking/reasoning output.
     * Channels can use this to show "thinking" or "typing" indicator to users.
     *
     * @param content Thinking content fragment
     * @param isLast Whether this is the last thinking fragment
     */
    data class ThinkingStreamEvent(
        val content: String,
        val isLast: Boolean,
    ) : AgentStreamEvent()

    /**
     * End Stream Event
     *
     * Signals the end of the streaming output.
     * For batch channels: triggers sending the merged complete message.
     *
     * @param fullContent Optional merged complete text content (for batch channels)
     */
    data class EndStreamEvent(
        val fullContent: String? = null,
    ) : AgentStreamEvent()

    /**
     * Error Stream Event
     *
     * Represents an error during AI processing.
     * Carries structured error information for user-facing error messages.
     *
     * ChannelChatService formats the final user message as:
     * `[error-code][request-id][user-readable message]`
     *
     * @param code Error code from HarnaxErrorCode (e.g., "6001")
     * @param message User-readable error description (from HarnaxErrorCode.format())
     * @param requestId Request identifier for tracing/debugging
     * @param cause Optional exception cause (for logging, not exposed to user)
     */
    data class ErrorStreamEvent(
        val code: String,
        val message: String,
        val requestId: String = "",
        val cause: Throwable? = null,
    ) : AgentStreamEvent()
}
