package com.vipamp.vipclaw.agent.chat

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.agentscope.core.model.ChatUsage

/**
 * @Author: heqingsong
 * @Date: 2026/4/1
 * @Description: ChatEvent
 * @Project: vipclaw
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "eventType"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = StreamThinkingChatEvent::class, name = "ThinkingEvent"),
    JsonSubTypes.Type(value = StreamTextChatEvent::class, name = "TextEvent"),
    JsonSubTypes.Type(value = ToolConfirmChatEvent::class, name = "ToolConfirmEvent"),
    JsonSubTypes.Type(value = CallToolChatEvent::class, name = "CallToolEvent"),
    JsonSubTypes.Type(value = ToolResultChatEvent::class, name = "ToolResultEvent")
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
    val timestamp: Long
) {
    companion object {
        fun fromChatUsage(chatUsage: ChatUsage?): TokenUsage? {
            if (chatUsage == null) {
                return null as TokenUsage?
            }
            return TokenUsage(
                chatUsage.inputTokens,
                chatUsage.outputTokens,
                chatUsage.totalTokens,
                chatUsage.time,
                System.currentTimeMillis()
            )
        }
    }
}

data class StreamThinkingChatEvent(
    val message: String,
    val isLast: Boolean,
    override val tokenUsage: TokenUsage?
) : ChatEvent {
    override val eventType: EventType = EventType.ThinkingEvent
}

data class StreamTextChatEvent(
    val message: String,
    val isLast: Boolean,
    override val tokenUsage: TokenUsage?
) : ChatEvent {
    override val eventType: EventType = EventType.TextEvent
}

data class ToolConfirmChatEvent(
    val pendingCallTools: List<PendingCallTool>,
    override val tokenUsage: TokenUsage?
) : ChatEvent {
    override val eventType: EventType = EventType.ToolConfirmEvent
}

data class PendingCallTool(
    val toolId: String,
    val toolName: String,
    val arguments: Map<String, Any>,
    val isDangerous: Boolean
)

data class CallToolChatEvent(
    val toolId: String,
    val toolName: String,
    val arguments: Map<String, Any>,
    override val tokenUsage: TokenUsage?
) : ChatEvent {
    override val eventType: EventType = EventType.CallToolEvent
}

data class ToolResultChatEvent(
    val toolId: String,
    val toolName: String,
    val message: String,
    val success: Boolean = true,
    override val tokenUsage: TokenUsage?
) : ChatEvent {
    override val eventType: EventType = EventType.ToolResultEvent
}

enum class EventType {
    ThinkingEvent,
    CallToolEvent,
    ToolResultEvent,
    TextEvent,
    ToolConfirmEvent
}