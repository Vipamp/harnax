package com.agnetix.harnax.channel.wechat

import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.ChannelCommunicationMode
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.dispatch.ChannelTurnExecutor
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.ChannelRequest
import com.agnetix.harnax.channel.sdk.message.RichMessage
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionState
import com.agnetix.harnax.channel.sdk.monitor.ChannelMetricsSink
import com.agnetix.harnax.channel.sdk.monitor.NoOpChannelMetricsSink
import com.agnetix.harnax.channel.sdk.service.ChannelChatService
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import org.slf4j.LoggerFactory

/**
 * WeChat Adapter
 * Implements SDK's ChannelAdaptor interface, integrates with WeChat iLink Bot
 *
 * WeChat iLink Bot uses a different communication pattern than Feishu/DingTalk:
 * - Feishu/DingTalk: Webhook (HTTP callback) or WebSocket (long connection)
 * - WeChat iLink: Long polling (getUpdates), actively pulls messages
 *
 * Core flow:
 * 1. QR code login → obtain bot identity
 * 2. Long polling retrieves messages → WeixinMessage → ChannelMessage
 * 3. AgentAdaptor processes ChannelMessage → AgentResponse
 * 4. ChannelMessage.senderId (from_user_id) → send reply via ILinkClient
 *
 * Notes:
 * - WeChat does not need Webhook callback, so verifySignature and parseMessage
 *   are mainly for SDK interface compatibility, actual messages are retrieved via WechatLongPollingMode
 * - sendMessage delegates to WechatLongPollingMode for sending
 */
class WechatAdaptor(
    private val botService: WechatBotService = WechatBotService(),
    turnExecutor: ChannelTurnExecutor = ChannelTurnExecutor.SHARED,
    metricsSink: ChannelMetricsSink = NoOpChannelMetricsSink,
) : ChannelAdaptor {

    private val logger = LoggerFactory.getLogger(WechatAdaptor::class.java)

    /**
     * Long polling communication mode instance
     */
    private val longPollingMode: WechatLongPollingMode = WechatLongPollingMode(botService, turnExecutor, metricsSink)

    override fun getType(): ChannelType = ChannelType.WECHAT

    /**
     * Verify callback signature
     *
     * WeChat iLink Bot uses long polling mode, no callback signature verification needed.
     * This method always returns true for SDK interface compatibility.
     */
    override fun verifySignature(request: ChannelRequest, channel: ChannelSpec): Boolean {
        // WeChat iLink uses long polling, no need for callback signature verification
        logger.debug("WeChat iLink does not require signature verification (long-polling mode)")
        return true
    }

    /**
     * Parse message
     *
     * WeChat iLink messages are retrieved by WechatLongPollingMode's polling thread,
     * then converted to ChannelMessage by WechatMessageConverter.
     *
     * This method is preserved for SDK interface compatibility, supports parsing messages from external ChannelRequest.
     * Can be extended here if needed to parse WeChat messages from HTTP requests.
     */
    override fun parseMessage(request: ChannelRequest): ChannelMessage {
        // WeChat iLink messages are received via long polling, not HTTP callback
        // This method is for interface compatibility, returns empty message
        logger.warn("WeChat iLink messages are received via long-polling, not HTTP callback")
        return ChannelMessage.builder()
            .sessionId("")
            .content(request.body)
            .channelType(ChannelType.WECHAT)
            .rawContent(request)
            .build()
    }

    /**
     * Build response
     *
     * WeChat iLink message sending is done via ILinkClient active push,
     * no need to build HTTP response body.
     */
    override fun buildResponse(reply: String, originalMessage: ChannelMessage): Any = mapOf(
        "code" to 0,
        "msg" to "success",
        "data" to mapOf(
            "content" to reply,
            "channelType" to "wechat",
        ),
    )

    /**
     * Push message to WeChat platform
     *
     * Send text message via ILinkClient, with typing indicator effect
     */
    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        longPollingMode.sendMessage(channel, sessionId, message)
    }

    /**
     * Send rich message to WeChat platform
     *
     * WeChat iLink currently mainly supports text messages, rich messages are downgraded to plain text
     */
    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        longPollingMode.sendRichMessage(channel, sessionId, richMessage)
    }

    /**
     * Send file to WeChat platform
     *
     * Sends file via ILinkClient's file API
     */
    override suspend fun sendFile(
        channel: ChannelSpec,
        sessionId: String,
        fileBytes: ByteArray,
        fileName: String,
        caption: String,
    ) {
        longPollingMode.sendFile(channel, sessionId, fileBytes, fileName, caption)
    }

    /**
     * WeChat does not support streaming output
     *
     * WeChat iLink only supports sending complete messages via ILinkClient,
     * cannot send partial text fragments incrementally.
     */
    override fun supportsStreamingOutput(): Boolean = false

    /**
     * Send typing indicator
     *
     * WeChat supports showing a "typing" status to the user via ILinkClient.
     * This is called by ChannelChatService to indicate the AI is processing the response.
     */
    override suspend fun sendTypingIndicator(channel: ChannelSpec, sessionId: String) {
        longPollingMode.sendTypingIndicator(channel, sessionId)
    }

    /**
     * Start WeChat channel
     *
     * Execute QR code login and start message polling
     *
     * @param channel Channel configuration
     * @param messageHandler Message processing callback
     */
    fun startChannel(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        longPollingMode.start(channel, messageHandler)
    }

    /**
     * Start WeChat channel with AI Agent integration
     *
     * Convenience method that creates ChannelChatService internally and
     * automatically handles the complete message processing flow:
     * 1. QR code login and start message polling
     * 2. Each incoming message is processed by ChannelChatService.chat():
     *    - Save user message to session
     *    - Call AgentAdaptor for AI processing
     *    - Determine output strategy (batch for WeChat)
     *    - Send AI reply via WeChat
     *    - Save AI reply to session
     *
     * WeChat uses batch mode (supportsStreamingOutput=false):
     * - Shows typing indicator while AI is processing
     * - Sends complete merged response when AI finishes
     *
     * @param channel Channel configuration
     * @param agentAdaptor AI Agent processor (e.g., ReActAgentAdaptor)
     * @param sessionManager Session manager for conversation history
     */
    override fun startChannelWithAgent(
        channel: ChannelSpec,
        agentAdaptor: AgentAdaptor,
        sessionManager: ChannelSessionManager,
        chatService: ChannelChatService?,
    ): Boolean {
        val effectiveChatService = chatService ?: ChannelChatService(sessionManager)
        val messageParser = WechatMessageParser()
        startChannel(channel) { message ->
            val agentRequest = messageParser.parse(message).withSessionId(channel.sessionId)
            effectiveChatService.chat(message, channel, agentAdaptor, this, agentRequest)
        }
        return true
    }

    override fun connectionState(channelId: Long): ChannelConnectionState = longPollingMode.connectionState(channelId)

    override fun connectionStates(): List<ChannelConnectionState> = longPollingMode.connectionStates()

    /**
     * Stop WeChat channel
     */
    override fun stopChannel(channel: ChannelSpec) {
        longPollingMode.stop(channel)
    }

    override fun shutdown() {
        longPollingMode.shutdown()
    }

    /**
     * Get communication mode instance
     * For scenarios that need direct access to communication mode
     */
    fun getCommunicationMode(): ChannelCommunicationMode = longPollingMode
}
