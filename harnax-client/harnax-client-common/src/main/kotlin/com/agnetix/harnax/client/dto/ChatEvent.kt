package com.agnetix.harnax.client.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Polymorphic SSE streaming event type for agent communication.
 *
 * Lightweight copy of harnax-protocol's ChatEvent without agentscope dependencies.
 * Jackson annotations enable automatic polymorphic (de)serialization.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "eventType",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ThinkingEvent::class, name = "ThinkingEvent"),
    JsonSubTypes.Type(value = TextEvent::class, name = "TextEvent"),
    JsonSubTypes.Type(value = ToolConfirmEvent::class, name = "ToolConfirmEvent"),
    JsonSubTypes.Type(value = CallToolEvent::class, name = "CallToolEvent"),
    JsonSubTypes.Type(value = ToolResultEvent::class, name = "ToolResultEvent"),
    JsonSubTypes.Type(value = EndEvent::class, name = "EndEvent"),
    JsonSubTypes.Type(value = ErrorEvent::class, name = "ErrorEvent"),
    JsonSubTypes.Type(value = KeepAliveEvent::class, name = "KeepAliveEvent"),
)
interface ChatEvent {
    val eventType: String
    val tokenUsage: TokenUsage?
}

/**
 * AI thinking content event (streaming).
 */
data class ThinkingEvent(
    val message: String,
    val isLast: Boolean,
    override val tokenUsage: TokenUsage? = null,
) : ChatEvent {
    override val eventType: String = "ThinkingEvent"
}

/**
 * AI text content event (streaming).
 */
data class TextEvent(
    val message: String,
    val isLast: Boolean,
    override val tokenUsage: TokenUsage? = null,
) : ChatEvent {
    override val eventType: String = "TextEvent"
}

/**
 * Tool confirmation request event - agent needs user approval to call tools.
 */
data class ToolConfirmEvent(
    val pendingCallTools: List<PendingCallTool>,
    override val tokenUsage: TokenUsage? = null,
) : ChatEvent {
    override val eventType: String = "ToolConfirmEvent"
}

/**
 * Pending tool call info in a ToolConfirmEvent.
 */
data class PendingCallTool(
    val toolId: String,
    val toolName: String,
    val arguments: Map<String, Any>,
    val isDangerous: Boolean,
)

/**
 * Tool call event - agent is calling a tool.
 */
data class CallToolEvent(
    val toolId: String,
    val toolName: String,
    val arguments: Map<String, Any>,
    override val tokenUsage: TokenUsage? = null,
) : ChatEvent {
    override val eventType: String = "CallToolEvent"
}

/**
 * Tool result event - tool execution completed.
 */
data class ToolResultEvent(
    val toolId: String,
    val toolName: String,
    val message: String,
    val success: Boolean = true,
    override val tokenUsage: TokenUsage? = null,
) : ChatEvent {
    override val eventType: String = "ToolResultEvent"
}

/**
 * End of stream event - agent output is complete.
 */
data class EndEvent(
    override val tokenUsage: TokenUsage? = null,
) : ChatEvent {
    override val eventType: String = "EndEvent"
}

/**
 * Error event - agent encountered an error during processing.
 */
data class ErrorEvent(
    val code: String,
    val message: String,
    override val tokenUsage: TokenUsage? = null,
) : ChatEvent {
    override val eventType: String = "ErrorEvent"
}

/**
 * Keep-alive event emitted while a run waits for a human confirmation.
 *
 * Carries no content and must not be rendered or treated as end-of-output; it exists only so the
 * caller's stream is not judged idle while the agent is legitimately waiting.
 */
data class KeepAliveEvent(
    override val tokenUsage: TokenUsage? = null,
) : ChatEvent {
    override val eventType: String = "KeepAliveEvent"
}
