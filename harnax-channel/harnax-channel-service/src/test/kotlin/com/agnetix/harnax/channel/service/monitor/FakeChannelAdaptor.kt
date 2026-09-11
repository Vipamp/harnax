package com.agnetix.harnax.channel.service.monitor

import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.ChannelRequest
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionState
import com.agnetix.harnax.channel.sdk.service.ChannelChatService
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager

/**
 * 只回答可观测性问题的 [ChannelAdaptor] 实现。真实适配器要构造就得有凭证、HTTP 客户端和平台长连接，
 * 而 [ChannelRuntimeMonitor] 做的只是把声明的频道和活的连接状态合并在一起，用不到那些东西。
 */
internal class FakeChannelAdaptor(
    private val type: ChannelType = ChannelType.FEISHU,
    private val states: MutableMap<Long, ChannelConnectionState> = mutableMapOf(),
) : ChannelAdaptor {

    fun report(
        channelId: Long,
        state: ChannelConnectionState,
    ) {
        states[channelId] = state
    }

    override fun getType(): ChannelType = type

    override fun verifySignature(
        request: ChannelRequest,
        channel: ChannelSpec,
    ): Boolean = true

    override fun parseMessage(request: ChannelRequest): ChannelMessage = throw UnsupportedOperationException("本测试不会调用消息解析")

    override fun buildResponse(
        reply: String,
        originalMessage: ChannelMessage,
    ): Any = reply

    override suspend fun sendMessage(
        channel: ChannelSpec,
        sessionId: String,
        message: String,
    ) {
    }

    override fun startChannelWithAgent(
        channel: ChannelSpec,
        agentAdaptor: AgentAdaptor,
        sessionManager: ChannelSessionManager,
        chatService: ChannelChatService?,
    ): Boolean = true

    override fun stopChannel(channel: ChannelSpec) {
    }

    override fun connectionState(channelId: Long): ChannelConnectionState = states[channelId] ?: ChannelConnectionState(channelId)

    override fun connectionStates(): List<ChannelConnectionState> = states.values.toList()
}
