package com.agnetix.harnax.channel.sdk.parser

import com.agnetix.harnax.agent.protocol.AgentRequest
import com.agnetix.harnax.channel.sdk.message.ChannelMessage

/**
 * Message Parser Interface
 *
 * Each channel has its own parser that converts incoming [ChannelMessage] content
 * into an [AgentRequest] (either ChatAgentRequest or CommandAgentRequest).
 *
 * Implementations define channel-specific parsing rules:
 * - Text starting with "/" may be parsed as command requests.
 *   Use [CommandAgentRequest.parse] as the shared slash-command parser.
 * - Other text is parsed as chat requests
 *
 * Usage:
 * ```
 * class FeishuMessageParser : MessageParser {
 *     override fun parse(message: ChannelMessage): AgentRequest {
 *         // delegate to CommandAgentRequest.parse() for "/" commands, otherwise as chat
 *     }
 * }
 * ```
 */
interface MessageParser {

    /**
     * Parse a ChannelMessage into an AgentRequest.
     *
     * @param message The incoming channel message
     * @return AgentRequest — either [ChatAgentRequest] or [CommandAgentRequest]
     */
    fun parse(message: ChannelMessage): AgentRequest
}
