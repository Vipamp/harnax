package com.agnetix.harnax.agent.protocol

import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.common.error.HarnaxException
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
    JsonSubTypes.Type(value = ErrorChatEvent::class, name = "ErrorEvent"),
    JsonSubTypes.Type(value = KeepAliveChatEvent::class, name = "KeepAliveEvent"),
)
sealed interface ChatEvent {
    val eventType: EventType
    val tokenUsage: TokenUsage?

    /**
     * Which team member run produced this event, or null when an ordinary single agent produced it.
     *
     * Members of a team share one root session and one SSE channel, so their events reach the user
     * mixed with the lead's. Neither `agentId` nor a display name can tell two runs of the same
     * member apart, and the user has to answer a confirmation aimed at one specific run.
     */
    val source: EventSource?
}

/**
 * Provenance of an event that came from a team member run rather than from the session's own agent.
 *
 * [childRunId] is the key the confirmation round trip goes back on: the user's answer has to reach the
 * member run that asked, not the lead's tool list (design section 9.2).
 */
data class EventSource(
    val teamId: Long,
    val teamName: String,
    val memberAgentId: Long,
    val memberAgentName: String,
    val childRunId: String,
    val childSessionId: String,
)

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
    override val source: EventSource? = null,
) : ChatEvent {
    override val eventType: EventType = EventType.ThinkingEvent
}

data class StreamTextChatEvent(
    val message: String,
    val isLast: Boolean,
    override val tokenUsage: TokenUsage?,
    override val source: EventSource? = null,
) : ChatEvent {
    override val eventType: EventType = EventType.TextEvent
}

data class ToolConfirmChatEvent(
    val pendingCallTools: List<PendingCallTool>,
    override val tokenUsage: TokenUsage?,
    override val source: EventSource? = null,
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
    override val source: EventSource? = null,
) : ChatEvent {
    override val eventType: EventType = EventType.CallToolEvent
}

data class ToolResultChatEvent(
    val toolId: String,
    val toolName: String,
    val message: String,
    val success: Boolean = true,
    override val tokenUsage: TokenUsage?,
    override val source: EventSource? = null,
) : ChatEvent {
    override val eventType: EventType = EventType.ToolResultEvent
}

/**
 * Signals the end of the AI output event stream.
 * Consumers can use this to determine when AI output is complete.
 *
 * @property attachments Files the run produced in the sandbox workspace. The batch path reports
 *   them on [ChatResponse.attachments]; carrying them here keeps the two output strategies on the
 *   same delivery contract instead of silently dropping files for streaming consumers.
 */
data class EndEventChatEvent(
    override val tokenUsage: TokenUsage? = null,
    val attachments: List<FileAttachment> = emptyList(),
    override val source: EventSource? = null,
) : ChatEvent {
    override val eventType: EventType = EventType.EndEvent
}

/**
 * Signals an error during agent processing.
 * Carries a structured error code from HarnaxErrorCode and a human-readable message.
 * Consumers (channel-service) convert this to a user-friendly error reply.
 */
data class ErrorChatEvent(
    val code: String,
    val message: String,
    override val tokenUsage: TokenUsage? = null,
    override val source: EventSource? = null,
) : ChatEvent {
    override val eventType: EventType = EventType.ErrorEvent

    companion object {
        /** Construct from a HarnaxException */
        fun from(e: HarnaxException) = ErrorChatEvent(code = e.code, message = e.message)

        /** Construct from a HarnaxErrorCode with optional format args */
        fun from(code: HarnaxErrorCode, vararg args: Any?): ErrorChatEvent {
            val ex = code.format(*args)
            return ErrorChatEvent(code = ex.code, message = ex.message)
        }
    }
}

/**
 * Keeps a live-but-silent stream open while a run waits for something outside the model.
 *
 * A member parked on a confirmation produces no event at all, and every hop of the streaming chain
 * kills a silent stream: session-router after 120s, channel-service after 180s. Both cut a run that
 * was still perfectly able to continue the moment a human answered. Consumers must not render this
 * and must not treat it as end-of-output — it carries no content and no token usage.
 */
data class KeepAliveChatEvent(
    override val source: EventSource? = null,
) : ChatEvent {
    override val eventType: EventType = EventType.KeepAliveEvent
    override val tokenUsage: TokenUsage? = null
}

/**
 * Re-stamps this event as coming from [source].
 *
 * Member runs emit plain events; the team runtime owns the copy that says who made them, so the
 * labelling happens once at the merge point instead of in every event converter.
 */
fun ChatEvent.withSource(source: EventSource): ChatEvent = when (this) {
    is StreamThinkingChatEvent -> copy(source = source)
    is StreamTextChatEvent -> copy(source = source)
    is ToolConfirmChatEvent -> copy(source = source)
    is CallToolChatEvent -> copy(source = source)
    is ToolResultChatEvent -> copy(source = source)
    is EndEventChatEvent -> copy(source = source)
    is ErrorChatEvent -> copy(source = source)
    is KeepAliveChatEvent -> copy(source = source)
}

enum class EventType {
    ThinkingEvent,
    CallToolEvent,
    ToolResultEvent,
    TextEvent,
    ToolConfirmEvent,
    EndEvent,
    ErrorEvent,
    KeepAliveEvent,
}
