package com.agnetix.harnax.channel.dingtalk

import com.agnetix.harnax.channel.dingtalk.client.PlatformHttpClient
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
 * DingTalk Adapter
 * Implements the SDK's ChannelAdaptor interface, integrates with DingTalk robots
 * over the Stream (WebSocket long connection) protocol.
 *
 * Like Feishu WebSocket mode, DingTalk Stream mode does not require a public
 * callback URL. Messages are delivered over the long connection managed by
 * [DingtalkStreamMode]; therefore verifySignature/parseMessage/buildResponse are
 * only kept for SDK interface compatibility and are not used in Stream mode.
 */
class DingtalkAdaptor(
    httpClient: PlatformHttpClient = PlatformHttpClient(),
    turnExecutor: ChannelTurnExecutor = ChannelTurnExecutor.SHARED,
    metricsSink: ChannelMetricsSink = NoOpChannelMetricsSink,
) : ChannelAdaptor {

    private val logger = LoggerFactory.getLogger(DingtalkAdaptor::class.java)

    private val streamMode: DingtalkStreamMode = DingtalkStreamMode(httpClient, turnExecutor, metricsSink)

    override fun getType(): ChannelType = ChannelType.DINGTALK

    override fun verifySignature(request: ChannelRequest, channel: ChannelSpec): Boolean {
        // Stream mode authentication is handled by the SDK; no callback signature.
        logger.debug("DingTalk Stream mode does not require callback signature verification")
        return true
    }

    override fun parseMessage(request: ChannelRequest): ChannelMessage {
        logger.warn("DingTalk Stream messages are received via long connection, not HTTP callback")
        return ChannelMessage.builder()
            .sessionId("")
            .content(request.body)
            .channelType(ChannelType.DINGTALK)
            .rawContent(request)
            .build()
    }

    override fun buildResponse(reply: String, originalMessage: ChannelMessage): Any = mapOf(
        "code" to 0,
        "msg" to "success",
        "data" to mapOf(
            "content" to reply,
            "channelType" to "dingtalk",
        ),
    )

    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        streamMode.sendMessage(channel, sessionId, message)
    }

    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        streamMode.sendRichMessage(channel, sessionId, richMessage)
    }

    override fun supportsStreamingOutput(): Boolean = false

    fun startChannel(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        streamMode.start(channel, messageHandler)
    }

    override fun startChannelWithAgent(
        channel: ChannelSpec,
        agentAdaptor: AgentAdaptor,
        sessionManager: ChannelSessionManager,
        chatService: ChannelChatService?,
    ): Boolean {
        val effectiveChatService = chatService ?: ChannelChatService(sessionManager)
        val messageParser = DingtalkMessageParser()
        startChannel(channel) { message ->
            val agentRequest = messageParser.parse(message).withSessionId(channel.sessionId)
            effectiveChatService.chat(message, channel, agentAdaptor, this, agentRequest)
        }
        return true
    }

    override fun connectionState(channelId: Long): ChannelConnectionState = streamMode.connectionState(channelId)

    override fun connectionStates(): List<ChannelConnectionState> = streamMode.connectionStates()

    override fun stopChannel(channel: ChannelSpec) {
        streamMode.stop(channel)
    }

    override fun shutdown() {
        streamMode.shutdown()
    }
}
