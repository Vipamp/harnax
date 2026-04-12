package com.vipamp.vipclaw.agent.chat

import io.agentscope.core.message.Msg
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ThinkingBlock
import io.agentscope.core.message.ToolResultBlock

/**
 * @Author: heqingsong
 * @Date: 2026/4/11
 * @Description: MsgExtractHelper
 * @Project: vipclaw
 */
object MsgExtractHelper {

    fun extractText(msg: Msg): String? {
        val contentBlocks = msg.getContentBlocks(TextBlock::class.java)
        if (contentBlocks.isEmpty()) return null
        val sb = StringBuilder()
        for (block in contentBlocks) {
            sb.append(block.text)
        }
        return sb.toString()
    }

    fun extractThinking(msg: Msg): String? {
        val contentBlocks = msg.getContentBlocks(ThinkingBlock::class.java)
        if (contentBlocks.isEmpty()) return null
        val sb = StringBuilder()
        for (block in contentBlocks) {
            sb.append(block.thinking)
        }
        return sb.toString()
    }

    fun extractToolOutput(result: ToolResultBlock): String {
        val output = result.output
        if (output.isEmpty()) return ""
        val sb = StringBuilder()
        for (block in output) {
            if (block is TextBlock) {
                sb.append(block.text)
            }
        }
        return sb.toString()
    }
}
