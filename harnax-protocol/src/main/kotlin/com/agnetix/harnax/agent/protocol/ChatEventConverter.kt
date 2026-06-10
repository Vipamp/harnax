package com.agnetix.harnax.agent.protocol

import io.agentscope.core.agent.Event
import io.agentscope.core.agent.EventType
import io.agentscope.core.message.MessageMetadataKeys.CHAT_USAGE
import io.agentscope.core.message.Msg
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.model.ChatUsage
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper

/**
 * ChatEventConverter - converts agentscope Event to ChatEvent.
 *
 * Handles the following event types:
 * - REASONING: text output, thinking, tool use blocks
 * - TOOL_RESULT: tool execution results
 *
 * Improvements over original:
 * - Null-safe msg handling
 * - Kotlin-native Map conversion
 */
object ChatEventConverter {

    private val objectMapper = ObjectMapper()

    /**
     * Convert an agentscope Event to a Flux of ChatEvents.
     * A single Event may produce multiple ChatEvents (e.g., text + thinking + tool calls).
     */
    fun convert(event: Event, dangerousTools: Set<String>): Flux<ChatEvent> {
        val msg: Msg = event.message ?: return Flux.empty()
        val tokenUsage = extractTokenUsage(msg)

        val events = mutableListOf<ChatEvent>()

        when (event.type) {
            EventType.REASONING -> {
                convertReasoningEvent(event, msg, tokenUsage, dangerousTools, events)
            }
            EventType.TOOL_RESULT -> {
                convertToolResultEvent(msg, tokenUsage, events)
            }
            else -> {
                // Unknown event types are silently ignored
            }
        }

        return Flux.fromIterable(events)
    }

    private fun convertReasoningEvent(
        event: Event,
        msg: Msg,
        tokenUsage: TokenUsage?,
        dangerousTools: Set<String>,
        events: MutableList<ChatEvent>,
    ) {
        // Extract text output
        val text = MsgExtractHelper.extractText(msg)
        if (!text.isNullOrEmpty() || event.isLast) {
            val messageToSend = if (event.isLast) "" else (text ?: "")
            events.add(StreamTextChatEvent(message = messageToSend, isLast = event.isLast, tokenUsage = tokenUsage))
        }

        // Extract thinking output
        val thinking = MsgExtractHelper.extractThinking(msg)
        if (!thinking.isNullOrEmpty() || event.isLast) {
            val messageToSend = if (event.isLast) "" else (thinking ?: "")
            events.add(StreamThinkingChatEvent(message = messageToSend, isLast = event.isLast, tokenUsage = tokenUsage))
        }

        // Extract tool use blocks (only on last event)
        if (event.isLast && msg.hasContentBlocks(ToolUseBlock::class.java)) {
            val toolCalls = msg.getContentBlocks(ToolUseBlock::class.java)
            val hasDangerous = toolCalls.any { tool -> dangerousTools.contains(tool.name) }

            if (hasDangerous) {
                val pending = toolCalls.map { tool ->
                    PendingCallTool(
                        toolId = tool.id,
                        toolName = tool.name,
                        arguments = convertInput(tool.input),
                        isDangerous = dangerousTools.contains(tool.name),
                    )
                }
                events.add(ToolConfirmChatEvent(pending, tokenUsage))
            } else {
                for (tool in toolCalls) {
                    events.add(
                        CallToolChatEvent(
                            toolId = tool.id,
                            toolName = tool.name,
                            arguments = convertInput(tool.input),
                            tokenUsage = tokenUsage,
                        ),
                    )
                }
            }
        }
    }

    private fun convertToolResultEvent(
        msg: Msg,
        tokenUsage: TokenUsage?,
        events: MutableList<ChatEvent>,
    ) {
        for (result in msg.getContentBlocks(ToolResultBlock::class.java)) {
            events.add(
                ToolResultChatEvent(
                    toolId = result.id,
                    toolName = result.name,
                    message = MsgExtractHelper.extractToolOutput(result),
                    success = true,
                    tokenUsage = tokenUsage,
                ),
            )
        }
    }

    private fun extractTokenUsage(msg: Msg): TokenUsage? {
        val chatUsage = try {
            msg.metadata[CHAT_USAGE] as? ChatUsage
        } catch (e: Exception) {
            null
        }
        return TokenUsage.fromChatUsage(chatUsage)
    }

    /**
     * Convert tool input to Map<String, Any> using Kotlin-native approach.
     */
    @Suppress("UNCHECKED_CAST")
    private fun convertInput(input: Any?): Map<String, Any> {
        if (input == null) return emptyMap()
        if (input is Map<*, *>) {
            return input.entries.associate { (k, v) -> (k?.toString() ?: "") to (v ?: "") }
        }
        return try {
            val json = objectMapper.writeValueAsString(input)
            objectMapper.readValue(json, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            mapOf("value" to input.toString())
        }
    }
}
