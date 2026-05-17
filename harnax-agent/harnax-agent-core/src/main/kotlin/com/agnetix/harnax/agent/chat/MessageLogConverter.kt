package com.agnetix.harnax.agent.chat

import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolUseBlock

/**
 * @Author: heqingsong
 * @Date: 2026/4/16
 * @Description: MessageLogConverter
 * @Project: harnax
 */
object MessageLogConverter {

    fun convert(msg: Msg): List<MessageLog> {
        val role = msg.role
        return when (role) {
            MsgRole.SYSTEM -> listOf(SystemMessageLog(message = msg.textContent))
            MsgRole.USER -> listOf(UserMessageLog(message = msg.textContent))
            MsgRole.TOOL -> {
                msg.getContentBlocks(ToolResultBlock::class.java)
                    .map { ToolResultMessageLog(it.name, MsgExtractHelper.extractToolOutput(it)) }
                    .toList()
            }

            MsgRole.ASSISTANT -> {
                val thinking = MsgExtractHelper.extractThinking(msg) ?: ""
                val text = MsgExtractHelper.extractText(msg) ?: ""
                val toolResultBLocks = msg.getContentBlocks(ToolUseBlock::class.java)
                val toolUseLogs = toolResultBLocks.map {
                    ToolUseLog(it.name, input = it.input as Map<String, Any>)
                }.toList()
                listOf(AssistantMessageLog(thinking, text, toolUseLogs))
            }
        }
    }
}
