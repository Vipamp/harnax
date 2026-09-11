package com.agnetix.harnax.channel.wecom

import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
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
 * WeChat Work (WeCom) Adapter.
 *
 * Integrates with the WeCom smart-robot over the WebSocket long connection
 * ([WecomWebSocketMode]). No public callback URL, no message encryption, and no
 * IP allowlist are required — authentication is done by the `aibot_subscribe`
 * handshake. verifySignature/parseMessage/buildResponse are only kept for SDK
 * interface compatibility and are not used in WebSocket mode.
 */
class WecomAdaptor(
    turnExecutor: ChannelTurnExecutor = ChannelTurnExecutor.SHARED,
    metricsSink: ChannelMetricsSink = NoOpChannelMetricsSink,
) : ChannelAdaptor {

    private val logger = LoggerFactory.getLogger(WecomAdaptor::class.java)

    private val webSocketMode: WecomWebSocketMode = WecomWebSocketMode(turnExecutor, metricsSink)

    override fun getType(): ChannelType = ChannelType.WECOM

    override fun verifySignature(request: ChannelRequest, channel: ChannelSpec): Boolean {
        logger.debug("WeCom WebSocket mode does not require callback signature verification")
        return true
    }

    override fun parseMessage(request: ChannelRequest): ChannelMessage {
        logger.warn("WeCom WebSocket messages are received via long connection, not HTTP callback")
        return ChannelMessage.builder()
            .sessionId("")
            .content(request.body)
            .channelType(ChannelType.WECOM)
            .rawContent(request)
            .build()
    }

    override fun buildResponse(reply: String, originalMessage: ChannelMessage): Any = mapOf(
        "code" to 0,
        "msg" to "success",
        "data" to mapOf(
            "content" to reply,
            "channelType" to "wecom",
        ),
    )

    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        webSocketMode.sendMessage(channel, sessionId, message)
    }

    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        webSocketMode.sendRichMessage(channel, sessionId, richMessage)
    }

    override fun supportsStreamingOutput(): Boolean = false

    fun startChannel(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        webSocketMode.start(channel, messageHandler)
    }

    override fun startChannelWithAgent(
        channel: ChannelSpec,
        agentAdaptor: AgentAdaptor,
        sessionManager: ChannelSessionManager,
        chatService: ChannelChatService?,
    ): Boolean {
        val effectiveChatService = chatService ?: ChannelChatService(sessionManager)
        val messageParser = WecomMessageParser()
        startChannel(channel) { message ->
            val agentRequest = messageParser.parse(message).withSessionId(channel.sessionId)
            effectiveChatService.chat(message, channel, agentAdaptor, this, agentRequest)
        }
        return true
    }

    override fun connectionState(channelId: Long): ChannelConnectionState = webSocketMode.connectionState(channelId)

    override fun connectionStates(): List<ChannelConnectionState> = webSocketMode.connectionStates()

    override fun stopChannel(channel: ChannelSpec) {
        webSocketMode.stop(channel)
    }

    override fun shutdown() {
        webSocketMode.shutdown()
    }
}
