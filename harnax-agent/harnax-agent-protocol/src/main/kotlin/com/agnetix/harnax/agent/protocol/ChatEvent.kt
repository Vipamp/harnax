package com.agnetix.harnax.agent.protocol

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.agentscope.core.model.ChatUsage

/**
 * ChatEvent - polymorphic streaming event type for agent communication.
 *
 * Used as the unified transport type across the entire streaming chain:
 * agent-service → session-router → channel-service.
 *
 * Jackson annotations enable automatic polymorphic (de)serialization
 * so that Flux<ChatEvent> can be sent over SSE without manual JSON handling.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "eventType",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = StreamThinkingChatEvent::class, name = "ThinkingEvent"),
    JsonSubTypes.Type(value = StreamTextChatEvent::class, name = "TextEvent"),
    JsonSubTypes.Type(value = ToolConfirmChatEvent::class, name = "ToolConfirmEvent"),
    JsonSubTypes.Type(value = CallToolChatEvent::class, name = "CallToolEvent"),
    JsonSubTypes.Type(value = ToolResultChatEvent::class, name = "ToolResultEvent"),
    JsonSubTypes.Type(value = EndEventChatEvent::class, name = "EndEvent"),
)
interface ChatEvent {
    val eventType: EventType
    val tokenUsage: TokenUsage?
}

data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val costTime: Double,
    val timestamp: Long,
) {
    companion object {
        fun fromChatUsage(chatUsage: ChatUsage?): TokenUsage? {
            if (chatUsage == null) return null
            return TokenUsage(
                inputTokens = chatUsage.inputTokens,
                outputTokens = chatUsage.outputTokens,
                totalTokens = chatUsage.totalTokens,
                costTime = chatUsage.time,
                timestamp = System.currentTimeMillis(),
            )
        }
    }
}

data class StreamThinkingChatEvent(
    val message: String,
    val isLast: Boolean,
    override val tokenUsage: TokenUsage?,
) : ChatEvent {
    override val eventType: EventType = EventType.ThinkingEvent
}

data class StreamTextChatEvent(
    val message: String,
    val isLast: Boolean,
    override val tokenUsage: TokenUsage?,
) : ChatEvent {
    override val eventType: EventType = EventType.TextEvent
}

data class ToolConfirmChatEvent(
    val pendingCallTools: List<PendingCallTool>,
    override val tokenUsage: TokenUsage?,
) : ChatEvent {
    override val eventType: EventType = EventType.ToolConfirmEvent
}

data class PendingCallTool(
    val toolId: String,
    val toolName: String,
    val arguments: Map<String, Any>,
    val isDangerous: Boolean,
)

data class CallToolChatEvent(
    val toolId: String,
    val toolName: String,
    val arguments: Map<String, Any>,
    override val tokenUsage: TokenUsage?,
) : ChatEvent {
    override val eventType: EventType = EventType.CallToolEvent
}

data class ToolResultChatEvent(
    val toolId: String,
    val toolName: String,
    val message: String,
    val success: Boolean = true,
    override val tokenUsage: TokenUsage?,
) : ChatEvent {
    override val eventType: EventType = EventType.ToolResultEvent
}

/**
 * Signals the end of the AI output event stream.
 * Consumers can use this to determine when AI output is complete.
 */
data class EndEventChatEvent(
    override val tokenUsage: TokenUsage? = null,
) : ChatEvent {
    override val eventType: EventType = EventType.EndEvent
}

enum class EventType {
    ThinkingEvent,
    CallToolEvent,
    ToolResultEvent,
    TextEvent,
    ToolConfirmEvent,
    EndEvent,
}
