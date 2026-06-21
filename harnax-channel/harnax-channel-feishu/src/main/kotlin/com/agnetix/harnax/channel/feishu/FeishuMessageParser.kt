package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.agent.protocol.AgentRequest
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageType
import com.agnetix.harnax.channel.sdk.parser.MessageParser
import org.slf4j.LoggerFactory

/**
 * Feishu Message Parser
 *
 * Parses incoming Feishu channel messages into AgentRequest:
 * - Messages starting with "/" are parsed as command requests via [CommandAgentRequest.parse]
 * - All other messages are parsed as chat requests
 *
 * Note: Feishu group chat messages may contain @_user_xxx mention prefix.
 * This parser strips the mention prefix before checking for "/" commands.
 */
class FeishuMessageParser : MessageParser {

    private val logger = LoggerFactory.getLogger(FeishuMessageParser::class.java)

    /**
     * Regex to strip Feishu @mention prefix (e.g., "@_user_1 ")
     */
    private val mentionPrefixRegex = Regex("^@_user_\\d+\\s*")

    override fun parse(message: ChannelMessage): AgentRequest {
        val sessionId = message.sessionId
        val rawContent = message.content.trim()

        // Handle IMAGE type: use imageUrls from ChannelMessage (already downloaded by FeishuWebSocketMode)
        if (message.messageType == MessageType.IMAGE && message.imageUrls.isNotEmpty()) {
            logger.debug("Parsed image message for session={}, imageCount={}", sessionId, message.imageUrls.size)
            return ChatAgentRequest(
                sessionId = sessionId,
                message = "Please analyze this image.",
                imageUrls = message.imageUrls,
            )
        }

        // Strip Feishu @mention prefix (common in group chats)
        val content = mentionPrefixRegex.replace(rawContent, "").trim()

        // Try to parse as slash command using the shared protocol utility
        val commandRequest = CommandAgentRequest.parse(sessionId, content)
        if (commandRequest != null) {
            logger.debug(
                "Parsed command '/{}' as {} (args='{}') for session={}",
                content.substring(1).substringBefore(' '),
                commandRequest.command,
                commandRequest.args,
                sessionId,
            )
            return commandRequest
        }

        // Default: chat request with the cleaned content (mention stripped)
        // Also pass through imageUrls from ChannelMessage (e.g., text messages with embedded images in post format)
        return ChatAgentRequest(
            sessionId = sessionId,
            message = content,
            imageUrls = message.imageUrls,
        )
    }
}
