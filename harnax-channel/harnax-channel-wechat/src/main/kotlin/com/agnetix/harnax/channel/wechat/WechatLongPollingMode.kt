package com.agnetix.harnax.channel.wechat

import com.agnetix.harnax.channel.sdk.adaptor.ChannelCommunicationMode
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.error.ChannelSendException
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MarkdownRichMessage
import com.agnetix.harnax.channel.sdk.message.RichMessage
import com.agnetix.harnax.channel.sdk.message.TextRichMessage
import com.github.wechat.ilink.sdk.core.config.ILinkConfig
import com.github.wechat.ilink.sdk.core.model.WeixinMessage
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * WeChat Long Polling Communication Mode
 *
 * WeChat iLink Bot uses long polling (getUpdates) to retrieve messages,
 * which is different from Feishu's Webhook/WebSocket mode:
 * - No public IP or domain required
 * - No callback URL registration needed
 * - Messages retrieved via active polling
 * - Suitable for intranet deployment and local development
 *
 * Communication flow:
 * 1. start() - Execute QR code login, start polling thread after successful login
 * 2. Polling thread continuously calls getUpdates() to retrieve messages
 * 3. Retrieved messages are converted to ChannelMessage and passed via messageHandler callback
 * 4. stop() - Stop polling and close ILinkClient
 */
class WechatLongPollingMode(
    private val botService: WechatBotService = WechatBotService(),
) : ChannelCommunicationMode {

    private val logger = LoggerFactory.getLogger(WechatLongPollingMode::class.java)

    // Tracks channels with active login/polling threads to prevent duplicate starts
    private val activeChannels = ConcurrentHashMap.newKeySet<Long>()

    override fun getModeName(): String = "long-polling"

    override fun isCallbackMode(): Boolean = false

    /**
     * Start long polling communication mode
     *
     * Flow:
     * 1. Create or get ILinkClient
     * 2. Execute QR code login
     * 3. Wait for user to scan code and login successfully
     * 4. Start message polling thread
     *
     * @param channel Channel configuration
     * @param messageHandler Message processing callback
     */
    override fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        val channelId = channel.id

        if (!activeChannels.add(channelId)) {
            logger.warn("Long-polling already active for channel: $channelId, skipping duplicate start")
            return
        }

        // Build iLink configuration
        val iLinkConfig = buildILinkConfig(channel)

        // Create client
        botService.getOrCreateClient(channelId, iLinkConfig)

        // Execute login and polling in new thread
        val thread = Thread({
            try {
                // Execute login
                val qrCodeContent = botService.executeLogin(channelId)
                logger.info("========================================")
                logger.info("请使用微信扫描以下二维码内容登录：")
                logger.info(qrCodeContent)
                logger.info("========================================")

                // Wait for login completion
                val loginFuture = botService.getLoginFuture(channelId)
                if (loginFuture != null) {
                    val context = loginFuture.get()
                    logger.info("WeChat bot login successful, botId = ${context.botId}")

                    // Start message polling after successful login
                    botService.startPolling(channelId) { messages ->
                        handleMessages(messages, channel, messageHandler)
                    }
                }
            } catch (e: Exception) {
                logger.error("WeChat long-polling mode startup failed for channel $channelId: ${e.message}", e)
            } finally {
                activeChannels.remove(channelId)
            }
        }, "wechat-login-$channelId")

        thread.isDaemon = true
        thread.start()
    }

    /**
     * Stop long polling communication mode
     */
    override fun stop(channel: ChannelSpec) {
        val channelId = channel.id
        activeChannels.remove(channelId)
        botService.closeClient(channelId)
        logger.info("WeChat long-polling mode stopped for channel $channelId")
    }

    /**
     * Send text message
     *
     * Sends message with typing indicator to simulate human typing effect
     */
    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        try {
            val channelId = channel.id
            // Send with typing indicator, typing duration calculated based on message length
            val typingMillis = minOf(message.length * 50L, 3000L)
            botService.sendTextWithTyping(channelId, sessionId, message, typingMillis)
            logger.debug("Message sent to $sessionId via WeChat")
        } catch (e: Exception) {
            throw ChannelSendException(
                channelType = ChannelType.WECHAT,
                platformErrorCode = null,
                message = "Failed to send WeChat message: ${e.message}",
                cause = e,
            )
        }
    }

    /**
     * Send rich message
     *
     * WeChat iLink currently mainly supports text messages, rich messages are downgraded to plain text
     */
    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        val textContent = when (richMessage) {
            is TextRichMessage -> richMessage.content
            is MarkdownRichMessage -> richMessage.content
            else -> richMessage.toString()
        }
        sendMessage(channel, sessionId, textContent)
    }

    /**
     * WeChat long polling mode does not support streaming output
     */
    override fun supportsStreamingOutput(): Boolean = false

    /**
     * Send typing indicator
     *
     * WeChat iLink supports sending a "typing" indicator to the user
     * while the AI is processing. This sends a typing status via ILinkClient.
     *
     * @param channel Channel configuration
     * @param sessionId Session ID (WeChat user ID, e.g., xxx@im.wechat)
     */
    override suspend fun sendTypingIndicator(channel: ChannelSpec, sessionId: String) {
        try {
            val channelId = channel.id
            botService.sendTextWithTyping(channelId, sessionId, "", 2000L)
        } catch (e: Exception) {
            logger.warn("Failed to send typing indicator for channel ${channel.id}: ${e.message}")
        }
    }

    /**
     * Process retrieved message list
     * Convert WeixinMessage to ChannelMessage and callback to upper layer
     */
    private fun handleMessages(
        messages: List<WeixinMessage>,
        channel: ChannelSpec,
        messageHandler: suspend (ChannelMessage) -> Unit,
    ) {
        for (msg in messages) {
            try {
                val channelMessage = WechatMessageConverter.toChannelMessage(msg, channel)
                // Use coroutine to call suspend function
                kotlinx.coroutines.runBlocking {
                    messageHandler(channelMessage)
                }
            } catch (e: Exception) {
                logger.error("Error handling WeChat message: ${e.message}", e)
            }
        }
    }

    /**
     * Build ILinkConfig from ChannelSpec
     */
    private fun buildILinkConfig(channel: ChannelSpec): ILinkConfig {
        val builder = ILinkConfig.builder()
            .connectTimeoutMs(35000)
            .readTimeoutMs(35000)
            .writeTimeoutMs(35000)
            .httpMaxRetries(3)
            .retryBaseDelayMs(1000)
            .retryMaxDelayMs(10000)
            .heartbeatEnabled(true)
            .heartbeatIntervalMs(30000)

        // Read additional configuration from ChannelSpec metadata
        channel.token?.let { builder.channelVersion(it) }

        return builder.build()
    }
}
