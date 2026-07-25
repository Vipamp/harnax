package com.agnetix.harnax.channel.wechat

import com.agnetix.harnax.agent.protocol.AgentRequest
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.parser.MessageParser
import org.slf4j.LoggerFactory

/**
 * WeChat Message Parser
 *
 * Parses incoming WeChat channel messages into AgentRequest:
 * - Messages starting with "/" are parsed as command requests via [CommandAgentRequest.parse]
 * - All other messages are parsed as chat requests
 */
class WechatMessageParser : MessageParser {

    private val logger = LoggerFactory.getLogger(WechatMessageParser::class.java)

    override fun parse(message: ChannelMessage): AgentRequest {
        val sessionId = message.sessionId
        val content = message.content.trim()

        val commandRequest = CommandAgentRequest.parse(sessionId, content)
        if (commandRequest != null) {
            logger.debug("Parsed command for session={}", sessionId)
            return commandRequest
        }

        return ChatAgentRequest(
            sessionId = sessionId,
            message = content,
            imageUrls = message.imageUrls,
        )
    }
}
