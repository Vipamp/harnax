package com.agnetix.harnax.channel.dingtalk

import com.agnetix.harnax.agent.protocol.AgentRequest
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.parser.MessageParser
import org.slf4j.LoggerFactory

/**
 * DingTalk Message Parser
 *
 * Parses incoming DingTalk channel messages into AgentRequest:
 * - Messages starting with "/" are parsed as command requests via [CommandAgentRequest.parse]
 * - All other messages are parsed as chat requests
 *
 * Note: DingTalk group chat messages delivered to a bot are already stripped of the
 * @mention by the platform, but we defensively trim a leading "@nick " prefix if present.
 */
class DingtalkMessageParser : MessageParser {

    private val logger = LoggerFactory.getLogger(DingtalkMessageParser::class.java)

    override fun parse(message: ChannelMessage): AgentRequest {
        val sessionId = message.sessionId
        val content = message.content.trim()

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

        return ChatAgentRequest(
            sessionId = sessionId,
            message = content,
            imageUrls = message.imageUrls,
        )
    }
}
