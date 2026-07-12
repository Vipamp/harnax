package com.agnetix.harnax.agent.protocol

import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.AgentEventType
import io.agentscope.core.event.ModelCallEndEvent
import io.agentscope.core.event.RequireUserConfirmEvent
import io.agentscope.core.event.TextBlockDeltaEvent
import io.agentscope.core.event.ThinkingBlockDeltaEvent
import io.agentscope.core.event.ToolResultEndEvent
import io.agentscope.core.message.ToolResultState
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper

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

    private val log = LoggerFactory.getLogger(ChatEventConverter::class.java)
    private val objectMapper: ObjectMapper = JsonMapper.builder().build()

    /**
     * Convert an agentscope AgentEvent to a Flux of ChatEvents.
     * A single AgentEvent may produce zero or one ChatEvent.
     *
     * Note: TOOL_CALL_START returns empty — the CallToolChatEvent is emitted at
     * TOOL_CALL_END (via convertToolCallEnd) after arguments have been fully accumulated.
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
            // Defer emission to TOOL_CALL_END after arguments are fully accumulated.
            Flux.empty()
        }
        AgentEventType.TOOL_RESULT_END -> {
            val resultEvent = event as ToolResultEndEvent
            val state = resultEvent.state
            val (success, resultMessage) = when (state) {
                ToolResultState.SUCCESS -> true to ""
                ToolResultState.DENIED -> false to "Tool execution denied by user"
                ToolResultState.ERROR -> false to "Tool execution failed"
                ToolResultState.INTERRUPTED -> false to "Tool execution interrupted"
                else -> true to ""
            }
            Flux.just(
                ToolResultChatEvent(
                    toolId = resultEvent.toolCallId,
                    toolName = resultEvent.toolCallName,
                    message = resultMessage,
                    success = success,
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
        AgentEventType.REQUIRE_USER_CONFIRM -> {
            val confirmEvent = event as RequireUserConfirmEvent
            val pendingTools = confirmEvent.toolCalls.map { toolUse ->
                PendingCallTool(
                    toolId = toolUse.id,
                    toolName = toolUse.name,
                    arguments = convertInput(toolUse.input),
                    isDangerous = toolUse.name in dangerousTools,
                )
            }
            Flux.just(ToolConfirmChatEvent(pendingCallTools = pendingTools, tokenUsage = null))
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
     * Emit CallToolChatEvent at TOOL_CALL_END with fully accumulated arguments.
     */
    fun convertToolCallEnd(
        toolCallId: String,
        toolCallName: String,
        argsJson: String,
        dangerousTools: Set<String>,
    ): Flux<ChatEvent> {
        val arguments = parseArguments(argsJson)
        return Flux.just(
            CallToolChatEvent(
                toolId = toolCallId,
                toolName = toolCallName,
                arguments = arguments,
                tokenUsage = null,
            ),
        )
    }

    /**
     * Parse a JSON string into a Map<String, Any>.
     * Returns empty map on parse failure.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseArguments(json: String): Map<String, Any> {
        if (json.isBlank()) return emptyMap()
        return try {
            objectMapper.readValue(json, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            log.warn("Failed to parse tool call arguments: {}", json, e)
            emptyMap()
        }
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
