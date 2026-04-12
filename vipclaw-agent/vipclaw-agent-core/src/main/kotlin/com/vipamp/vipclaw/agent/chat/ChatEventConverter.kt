package com.vipamp.vipclaw.agent.chat

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.agentscope.core.agent.Event
import io.agentscope.core.agent.EventType
import io.agentscope.core.message.MessageMetadataKeys.CHAT_USAGE
import io.agentscope.core.message.Msg
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.model.ChatUsage
import reactor.core.publisher.Flux
import java.util.Map

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: ChatEventConverter
 * @Project: vipclaw
 */
object ChatEventConverter {

    private val OBJECT_MAPPER = ObjectMapper()

    private fun convertInput(input: Any?): Map<String, Any> {
        val result = HashMap<String, Any>()
        if (input == null) {
            return result as Map<String, Any>
        }
        if (input is Map<*, *>) {
            for (entry in input.entrySet()) {
                result[entry.key?.toString() ?: ""] = entry.value
            }
            return result as Map<String, Any>
        }
        return try {
            val json = OBJECT_MAPPER.writeValueAsString(input)
            val map = OBJECT_MAPPER.readValue(json, object : TypeReference<Map<String, Any>>() {})
            for (entry in map.entrySet()) {
                result.put(entry.key, entry.value)
            }
            result as Map<String, Any>
        } catch (e: Exception) {
            result.put("value", input.toString())
            result as Map<String, Any>
        }
    }

    fun convert(event: Event, dangerousTools: Set<String>): Flux<ChatEvent> {
        val events: MutableList<ChatEvent> = ArrayList()
        val msg: Msg = event.message
        val tokenUsage = TokenUsage.fromChatUsage(msg.metadata[CHAT_USAGE] as ChatUsage?)
        when (event.type) {
            EventType.REASONING -> {
                val text: String? = MsgExtractHelper.extractText(msg)
                if (!text.isNullOrEmpty()) {
                    events.add(StreamTextChatEvent(message = text, event.isLast, tokenUsage = tokenUsage))
                }
                val thinking: String? = MsgExtractHelper.extractThinking(msg)
                if (!thinking.isNullOrEmpty()) {
                    events.add(StreamThinkingChatEvent(message = thinking, event.isLast, tokenUsage = tokenUsage))
                }
                if (event.isLast && msg.hasContentBlocks(ToolUseBlock::class.java)) {
                    val toolCalls = msg.getContentBlocks(ToolUseBlock::class.java)
                    val hasDangerous = toolCalls.stream()
                        .anyMatch { t: ToolUseBlock -> dangerousTools.contains(t.name) }
                    if (hasDangerous) {
                        val pending: MutableList<PendingCallTool> = ArrayList()
                        for (tool in toolCalls) {
                            pending.add(
                                PendingCallTool(
                                    tool.id,
                                    tool.name,
                                    convertInput(tool.input) as kotlin.collections.Map<String, Any>,
                                    dangerousTools.contains(tool.name)
                                )
                            )
                        }
                        events.add(ToolConfirmChatEvent(pending, tokenUsage))
                    } else {
                        for (tool in toolCalls) {
                            events.add(
                                CallToolChatEvent(
                                    tool.id,
                                    tool.name,
                                    convertInput(tool.input) as kotlin.collections.Map<String, Any>,
                                    tokenUsage
                                )
                            )
                        }
                    }
                }
            }

            EventType.TOOL_RESULT -> {
                for (result in msg.getContentBlocks(ToolResultBlock::class.java)) {
                    events.add(
                        ToolResultChatEvent(
                            result.id,
                            result.name,
                            MsgExtractHelper.extractToolOutput(result),
                            true,
                            tokenUsage
                        )
                    )
                }
            }
            else -> {}
        }
        return Flux.fromIterable(events)
    }
}
