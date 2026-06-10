package com.agnetix.harnax.agent.protocol

import io.agentscope.core.message.Msg
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ThinkingBlock
import io.agentscope.core.message.ToolResultBlock

/**
 * MsgExtractHelper - utility for extracting structured data from agentscope Msg objects.
 */
object MsgExtractHelper {

    fun extractText(msg: Msg): String? {
        val contentBlocks = msg.getContentBlocks(TextBlock::class.java)
        if (contentBlocks.isEmpty()) return null
        return contentBlocks.joinToString("") { it.text }
    }

    fun extractThinking(msg: Msg): String? {
        val contentBlocks = msg.getContentBlocks(ThinkingBlock::class.java)
        if (contentBlocks.isEmpty()) return null
        return contentBlocks.joinToString("") { it.thinking }
    }

    fun extractToolOutput(result: ToolResultBlock): String {
        val output = result.output
        if (output.isEmpty()) return ""
        return output.filterIsInstance<TextBlock>().joinToString("") { it.text }
    }
}
