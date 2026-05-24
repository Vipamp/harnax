package com.agnetix.harnax.channel.service.manager

import com.agnetix.harnax.channel.feishu.FeishuAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.service.ChannelChatService
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import com.agnetix.harnax.channel.service.adaptor.RouterAgentAdaptor
import com.agnetix.harnax.channel.service.client.RouterClient
import com.agnetix.harnax.channel.wechat.WechatAdaptor
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Channel Manager.
 * Manages channel message processing by routing messages through
 * the session-router to agent-service, then returning results to the channel.
 *
 * Uses ChannelChatService from the SDK with a RouterAgentAdaptor that
 * delegates agent processing to the remote agent-service via session-router.
 */
@Service
class ChannelManager(
    private val routerClient: RouterClient,
    private val sessionManager: ChannelSessionManager,
    private val wechatAdaptor: WechatAdaptor,
    private val feishuAdaptor: FeishuAdaptor,
) {

    private val log = LoggerFactory.getLogger(ChannelManager::class.java)

    /** ChannelChatService instance using RouterAgentAdaptor */
    private val chatService = ChannelChatService(sessionManager)

    /** RouterAgentAdaptor that delegates to agent-service via session-router */
    private val routerAgentAdaptor = RouterAgentAdaptor(routerClient)

    /**
     * Handle an incoming channel message.
     * Routes the message through the session-router to agent-service
     * and sends the response back through the channel.
     *
     * This method uses ChannelChatService from the SDK, which provides
     * the full orchestration: session saving, agent processing, and response sending.
     *
     * @param message The channel message to process
     * @param channel The channel configuration
     */
    suspend fun handleMessage(message: ChannelMessage, channel: ChannelSpec) {
        log.info("Received message from channel ${channel.id}, session=${message.sessionId}")

        val channelAdaptor = getAdaptor(channel.type)

        try {
            chatService.chat(message, channel, routerAgentAdaptor, channelAdaptor)
        } catch (e: Exception) {
            log.error("Error handling message from channel ${channel.id}: ${e.message}", e)
        }
    }

    /**
     * Get the appropriate ChannelAdaptor for the given channel type.
     */
    private fun getAdaptor(type: ChannelType): ChannelAdaptor = when (type) {
        ChannelType.WECHAT -> wechatAdaptor
        ChannelType.FEISHU -> feishuAdaptor
        else -> throw IllegalArgumentException("Unsupported channel type: $type")
    }

    /**
     * Auto-start channels on application startup (placeholder).
     * In production, this would load channel configurations from the database.
     */
    @PostConstruct
    fun init() {
        log.info("ChannelManager initialized. Channel auto-start is configured per application settings.")
        // TODO: Load channel configurations from database and start them
    }
}
