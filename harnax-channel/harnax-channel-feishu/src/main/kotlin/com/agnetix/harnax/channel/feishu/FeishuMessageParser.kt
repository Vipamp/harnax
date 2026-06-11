package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.agent.protocol.AgentRequest
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.parser.MessageParser
import org.slf4j.LoggerFactory

/**
 * Feishu Message Parser
 *
 * Parses incoming Feishu channel messages into AgentRequest:
 * - Messages starting with "/" are parsed as command requests
 * - All other messages are parsed as chat requests
 *
 * Command keyword mapping (case-insensitive):
 * - /clear → CommandType.CLEAR
 * - /interrupt, /stop → CommandType.INTERRUPT
 * - /compact → CommandType.COMPACT
 * - Unknown commands fall back to ChatAgentRequest with the full message
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

    /**
     * Command keyword → CommandType mapping
     */
    private val commandMap: Map<String, CommandType> = mapOf(
        "clear" to CommandType.CLEAR,
        "interrupt" to CommandType.INTERRUPT,
        "stop" to CommandType.INTERRUPT,
        "compact" to CommandType.COMPACT,
        "approve" to CommandType.APPROVE,
    )

    override fun parse(message: ChannelMessage): AgentRequest {
        val sessionId = message.sessionId
        val rawContent = message.content.trim()

        // Strip Feishu @mention prefix (common in group chats)
        val content = mentionPrefixRegex.replace(rawContent, "").trim()

        // Check if it's a command (starts with "/")
        if (content.startsWith("/")) {
            val keyword = content.substring(1).trim().lowercase()

            val commandType = commandMap[keyword]
            if (commandType != null) {
                logger.debug("Parsed command '/{}' as {} for session={}", keyword, commandType, sessionId)
                return CommandAgentRequest(sessionId = sessionId, command = commandType)
            }

            // Unknown command keyword — treat as chat with full original content
            logger.debug("Unknown command '/{}', treating as chat for session={}", keyword, sessionId)
        }

        // Default: chat request with the cleaned content (mention stripped)
        return ChatAgentRequest(sessionId = sessionId, message = content)
    }
}
