package com.agnetix.harnax.channel.service.manager

import com.agnetix.harnax.agent.protocol.AgentRequest
import com.agnetix.harnax.channel.feishu.FeishuMessageParser
import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.parser.MessageParser
import com.agnetix.harnax.channel.sdk.service.ChannelChatService
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import com.agnetix.harnax.channel.service.adaptor.RouterAgentAdaptor
import com.agnetix.harnax.channel.service.client.RouterClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Channel Manager.
 * Manages channel message processing by routing messages through
 * the session-router to agent-service, then returning results to the channel.
 *
 * Slash commands (/approve, /deny, /clear, etc.) are parsed by the channel's
 * MessageParser and sent as CommandAgentRequest. The agent-service's
 * executeCommand() handles them, including HITL approve/deny which delegates
 * to the confirm flow internally.
 */
@Service
class ChannelManager(
    private val routerClient: RouterClient,
    private val sessionManager: ChannelSessionManager,
    private val adaptorRegistry: ChannelAdaptorRegistry,
) {

    private val log = LoggerFactory.getLogger(ChannelManager::class.java)

    /** ChannelChatService instance for processing channel messages */
    private val chatService = ChannelChatService(
        sessionManager = sessionManager,
        workspaceFileDownloader = { sessionId, filePath ->
            routerClient.downloadWorkspaceFile(sessionId, filePath)
        },
    )

    /** RouterAgentAdaptor that delegates to agent-service via session-router */
    private val routerAgentAdaptor = RouterAgentAdaptor(routerClient)

    /** Feishu-specific message parser */
    private val feishuMessageParser = FeishuMessageParser()

    /** DingTalk-specific message parser */
    private val dingtalkMessageParser = com.agnetix.harnax.channel.dingtalk.DingtalkMessageParser()

    /** WeCom-specific message parser */
    private val wecomMessageParser = com.agnetix.harnax.channel.wecom.WecomMessageParser()

    /** WeChat-specific message parser */
    private val wechatMessageParser = com.agnetix.harnax.channel.wechat.WechatMessageParser()

    /**
     * Handle an incoming channel message.
     * Routes the message through the session-router to agent-service
     * and sends the response back through the channel.
     *
     * Slash commands (including /approve and /deny) are parsed by the
     * MessageParser and handled by agent-service's executeCommand().
     */
    suspend fun handleMessage(message: ChannelMessage, channel: ChannelSpec) {
        log.info("Received message from channel ${channel.id}, session=${message.sessionId}")

        val channelAdaptor = getAdaptor(channel.type)
        val messageParser = getParser(channel.type)

        // Parse channel message into AgentRequest
        val agentRequest = messageParser.parse(message)
        log.info("Parsed message as {} for session={}", agentRequest.type, message.sessionId)

        try {
            chatService.chat(message, channel, routerAgentAdaptor, channelAdaptor, agentRequest)
        } catch (e: Exception) {
            log.error("Error handling message from channel ${channel.id}: ${e.message}", e)
        }
    }

    /**
     * Get the appropriate ChannelAdaptor for the given channel type.
     */
    private fun getAdaptor(type: ChannelType): ChannelAdaptor = adaptorRegistry.get(type)

    /**
     * Get the appropriate MessageParser for the given channel type.
     * Each channel has its own parser that converts incoming messages
     * into AgentRequest (ChatAgentRequest or CommandAgentRequest).
     */
    private fun getParser(type: ChannelType): MessageParser = when (type) {
        ChannelType.FEISHU -> feishuMessageParser
        ChannelType.DINGTALK -> dingtalkMessageParser
        ChannelType.WECOM -> wecomMessageParser
        ChannelType.WECHAT -> wechatMessageParser
        else -> defaultMessageParser
    }

    companion object {
        /** Default parser that always creates ChatAgentRequest */
        private val defaultMessageParser: MessageParser = object : MessageParser {
            override fun parse(message: ChannelMessage): AgentRequest = com.agnetix.harnax.agent.protocol.ChatAgentRequest(
                sessionId = message.sessionId,
                message = message.content,
            )
        }
    }
}
