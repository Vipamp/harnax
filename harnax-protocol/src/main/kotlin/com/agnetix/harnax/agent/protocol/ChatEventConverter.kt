package com.agnetix.harnax.agent.protocol

import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.AgentEventType
import io.agentscope.core.event.ModelCallEndEvent
import io.agentscope.core.event.TextBlockDeltaEvent
import io.agentscope.core.event.ThinkingBlockDeltaEvent
import io.agentscope.core.event.ToolCallStartEvent
import io.agentscope.core.event.ToolResultEndEvent
import reactor.core.publisher.Flux

/**
 * ChatEventConverter — converts agentscope 2.0.0 AgentEvent to ChatEvent.
 *
 * In 2.0.0, the old Event/EventType system was replaced by AgentEvent/AgentEventType.
 * The new event system provides fine-grained streaming events:
 * - TextBlockDeltaEvent → incremental text
 * - ThinkingBlockDeltaEvent → incremental thinking
 * - ToolCallStartEvent → tool call initiated
 * - ToolResultEndEvent → tool call completed
 * - ModelCallEndEvent → may contain token usage
 */
object ChatEventConverter {

    /**
     * Convert an agentscope AgentEvent to a Flux of ChatEvents.
     * A single AgentEvent may produce zero or one ChatEvent.
     */
    fun convert(event: AgentEvent, dangerousTools: Set<String>): Flux<ChatEvent> = when (event.type) {
        AgentEventType.TEXT_BLOCK_DELTA -> {
            val textEvent = event as TextBlockDeltaEvent
            val tokenUsage = extractTokenUsageFromEvent(event)
            Flux.just(
                StreamTextChatEvent(
                    message = textEvent.delta ?: "",
                    isLast = false,
                    tokenUsage = tokenUsage,
                ),
            )
        }
        AgentEventType.TEXT_BLOCK_END -> {
            Flux.just(
                StreamTextChatEvent(message = "", isLast = true, tokenUsage = null),
            )
        }
        AgentEventType.THINKING_BLOCK_DELTA -> {
            val thinkingEvent = event as ThinkingBlockDeltaEvent
            Flux.just(
                StreamThinkingChatEvent(
                    message = thinkingEvent.delta ?: "",
                    isLast = false,
                    tokenUsage = null,
                ),
            )
        }
        AgentEventType.THINKING_BLOCK_END -> {
            Flux.just(
                StreamThinkingChatEvent(message = "", isLast = true, tokenUsage = null),
            )
        }
        AgentEventType.TOOL_CALL_START -> {
            val toolEvent = event as ToolCallStartEvent
            val tokenUsage = extractTokenUsageFromEvent(event)
            Flux.just(
                CallToolChatEvent(
                    toolId = toolEvent.toolCallId,
                    toolName = toolEvent.toolCallName,
                    arguments = emptyMap(),
                    tokenUsage = tokenUsage,
                ),
            )
        }
        AgentEventType.TOOL_RESULT_END -> {
            val resultEvent = event as ToolResultEndEvent
            Flux.just(
                ToolResultChatEvent(
                    toolId = resultEvent.toolCallId,
                    toolName = resultEvent.toolCallName,
                    message = "",
                    success = true,
                    tokenUsage = null,
                ),
            )
        }
        AgentEventType.MODEL_CALL_END -> {
            val modelEnd = event as ModelCallEndEvent
            val tokenUsage = extractTokenUsageFromModelEnd(modelEnd)
            if (tokenUsage != null) {
                Flux.just(
                    StreamTextChatEvent(message = "", isLast = false, tokenUsage = tokenUsage),
                )
            } else {
                Flux.empty()
            }
        }
        else -> Flux.empty()
    }

    private fun extractTokenUsageFromEvent(event: AgentEvent): TokenUsage? = null

    private fun extractTokenUsageFromModelEnd(event: ModelCallEndEvent): TokenUsage? {
        val chatUsage = try {
            event.usage
        } catch (e: Exception) {
            null
        }
        return TokenUsage.fromChatUsage(chatUsage)
    }

    /**
     * Convert tool input to Map<String, Any>.
     */
    @Suppress("UNCHECKED_CAST")
    fun convertInput(input: Any?): Map<String, Any> {
        if (input == null) return emptyMap()
        if (input is Map<*, *>) {
            return input.entries.associate { (k, v) -> (k?.toString() ?: "") to (v ?: "") }
        }
        return mapOf("value" to input.toString())
    }
}
