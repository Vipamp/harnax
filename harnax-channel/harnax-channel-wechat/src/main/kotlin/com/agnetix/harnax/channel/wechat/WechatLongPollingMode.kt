package com.agnetix.harnax.channel.wechat

import com.agnetix.harnax.channel.sdk.adaptor.ChannelCommunicationMode
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.dispatch.ChannelTurnExecutor
import com.agnetix.harnax.channel.sdk.error.ChannelSendException
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MarkdownRichMessage
import com.agnetix.harnax.channel.sdk.message.RichMessage
import com.agnetix.harnax.channel.sdk.message.TextRichMessage
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionState
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionTracker
import com.agnetix.harnax.channel.sdk.monitor.ChannelMetricsSink
import com.agnetix.harnax.channel.sdk.monitor.NoOpChannelMetricsSink
import com.github.wechat.ilink.sdk.core.config.ILinkConfig
import com.github.wechat.ilink.sdk.core.login.LoginContext
import com.github.wechat.ilink.sdk.core.model.WeixinMessage
import org.slf4j.LoggerFactory
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private val turnExecutor: ChannelTurnExecutor = ChannelTurnExecutor.SHARED,
    private val metricsSink: ChannelMetricsSink = NoOpChannelMetricsSink,
) : ChannelCommunicationMode {

    private val logger = LoggerFactory.getLogger(WechatLongPollingMode::class.java)
    private val objectMapper = jacksonObjectMapper()

    private val tracker = ChannelConnectionTracker(metricsSink, ChannelType.WECHAT.code)

    /** One entry per channel with a login/polling thread we own; also prevents duplicate starts. */
    private val listeners = ConcurrentHashMap<Long, Listener>()

    private inner class Listener(val channel: ChannelSpec) {
        val closing = AtomicBoolean(false)

        @Volatile
        var thread: Thread? = null

        @Volatile
        var messageHandler: (suspend (ChannelMessage) -> Unit)? = null
    }

    override fun getModeName(): String = "long-polling"

    override fun isCallbackMode(): Boolean = false

    override fun connectionState(channelId: Long): ChannelConnectionState = tracker.state(channelId)

    override fun connectionStates(): List<ChannelConnectionState> = tracker.snapshot()

    /**
     * Start long polling communication mode
     *
     * Flow:
     * 1. Create or get ILinkClient
     * 2. Execute QR code login (or resume with stored credentials)
     * 3. Start message polling thread
     *
     * @param channel Channel configuration
     * @param messageHandler Message processing callback
     */
    override fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        val channelId = channel.id
        val existing = listeners[channelId]
        if (existing != null && !existing.closing.get()) {
            logger.warn("Long-polling already active for channel: $channelId, skipping duplicate start")
            return
        }

        val holder = Listener(channel)
        holder.messageHandler = messageHandler
        if (listeners.putIfAbsent(channelId, holder) != null) {
            logger.warn("Long-polling for channel $channelId was started concurrently, ignoring this request")
            return
        }
        tracker.markConnecting(channelId)

        val iLinkConfig = buildILinkConfig(channel)
        val credentials = parseCredentials(channel.configJson)
        val thread = Thread(
            {
                if (credentials != null) {
                    resumeWithCredentials(holder, credentials, iLinkConfig)
                } else {
                    loginWithQrCode(holder, iLinkConfig)
                }
            },
            if (credentials != null) "wechat-resume-$channelId" else "wechat-login-$channelId",
        )
        thread.isDaemon = true
        holder.thread = thread
        thread.start()
    }

    /**
     * Preferred path: connect with stored credentials obtained via the admin-side scan
     * flow — no QR scan needed here.
     */
    private fun resumeWithCredentials(
        holder: Listener,
        credentials: LoginContext,
        config: ILinkConfig,
    ) {
        val channelId = holder.channel.id
        if (holder.closing.get()) {
            listeners.remove(channelId, holder)
            return
        }
        try {
            botService.createClientFromCredentials(channelId, credentials, config)
            logger.info("WeChat channel $channelId connecting with stored credentials, botId=${credentials.botId}")
            startPolling(holder)
        } catch (e: Exception) {
            logger.error("WeChat resume-connect failed for channel $channelId: ${e.message}", e)
            runCatching { botService.closeClient(channelId) }
            fail(holder, e.message ?: "resume-connect failed")
        }
    }

    /**
     * Fallback path: no stored credentials, perform interactive QR login.
     * Used mainly for local development; production should authenticate via admin.
     */
    private fun loginWithQrCode(
        holder: Listener,
        config: ILinkConfig,
    ) {
        val channelId = holder.channel.id
        try {
            botService.getOrCreateClient(channelId, config)
            val qrCodeContent = botService.executeLogin(channelId)
            logger.info("========================================")
            logger.info("请使用微信扫描以下二维码内容登录：")
            logger.info(qrCodeContent)
            logger.info("========================================")

            val loginFuture = botService.getLoginFuture(channelId)
            if (loginFuture == null) {
                fail(holder, "login future unavailable")
                return
            }
            val context = loginFuture.get(LOGIN_WAIT_MINUTES, TimeUnit.MINUTES)
            if (holder.closing.get()) {
                runCatching { botService.closeClient(channelId) }
                return
            }
            logger.info("WeChat bot login successful, botId = ${context.botId}")
            startPolling(holder)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(holder, "interrupted")
        } catch (e: Exception) {
            logger.error("WeChat long-polling mode startup failed for channel $channelId: ${e.message}", e)
            fail(holder, e.message ?: "login failed")
        }
    }

    private fun startPolling(holder: Listener) {
        val channelId = holder.channel.id
        botService.startPolling(
            channelId,
            onStopped = { reason ->
                if (!holder.closing.get()) {
                    fail(holder, reason ?: "polling stopped")
                }
            },
        ) { messages ->
            handleMessages(holder, messages)
        }
        tracker.markConnected(channelId)
    }

    private fun fail(
        holder: Listener,
        reason: String,
    ) {
        val channelId = holder.channel.id
        listeners.remove(channelId, holder)
        tracker.markFailed(channelId, reason)
    }

    /**
     * Stop long polling communication mode
     */
    override fun stop(channel: ChannelSpec) {
        val channelId = channel.id
        val holder = listeners.remove(channelId)
        if (holder == null) {
            tracker.markStopped(channelId)
            logger.warn("No active WeChat long-polling for channel $channelId, nothing to stop")
            return
        }
        // Set first so the polling stop-callback knows this shutdown was intentional.
        holder.closing.set(true)
        runCatching { botService.closeClient(channelId) }
            .onFailure { logger.warn("Failed to close ILinkClient for channel $channelId: ${it.message}") }
        val thread = holder.thread
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            runCatching { thread.join(STOP_JOIN_MS) }
            if (thread.isAlive) {
                logger.warn("WeChat login/polling thread for channel $channelId did not exit within ${STOP_JOIN_MS}ms")
            }
        }
        tracker.markStopped(channelId)
        logger.info("WeChat long-polling mode stopped for channel $channelId")
    }

    /** Stop every channel (application shutdown). */
    fun shutdown() {
        listeners.values.forEach { holder ->
            runCatching { stop(holder.channel) }
        }
        listeners.clear()
        botService.closeAll()
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
     * Send file message
     *
     * Sends file via ILinkClient's sendFile API
     */
    suspend fun sendFile(channel: ChannelSpec, sessionId: String, fileBytes: ByteArray, fileName: String, caption: String) {
        try {
            val channelId = channel.id
            botService.sendFile(channelId, sessionId, fileBytes, fileName, caption)
            logger.debug("File $fileName sent to $sessionId via WeChat")
        } catch (e: Exception) {
            throw ChannelSendException(
                channelType = ChannelType.WECHAT,
                platformErrorCode = null,
                message = "Failed to send WeChat file: ${e.message}",
                cause = e,
            )
        }
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
            // Only start the typing indicator without sending any text.
            // Previously this used sendTextWithTyping(channelId, sessionId, "", 2000L),
            // which made the iLink SDK send an EMPTY message bubble to the user.
            // The indicator is cleared by stopTyping() inside sendTextWithTyping()
            // when the actual reply is sent via sendMessage().
            botService.startTyping(channelId, sessionId)
        } catch (e: Exception) {
            logger.warn("Failed to send typing indicator for channel ${channel.id}: ${e.message}")
        }
    }

    /**
     * Process retrieved message list
     * Convert WeixinMessage to ChannelMessage and hand each one to the turn executor.
     *
     * The polling thread must get back to getUpdates() immediately: waiting for the
     * agent turn here stalls the update cursor and makes the platform see a dead bot.
     */
    private fun handleMessages(
        holder: Listener,
        messages: List<WeixinMessage>,
    ) {
        val channelId = holder.channel.id
        val handler = holder.messageHandler ?: run {
            logger.warn("No message handler registered for channel: $channelId")
            return
        }
        for (msg in messages) {
            if (holder.closing.get() || listeners[channelId] !== holder) {
                logger.debug("Dropping WeChat message for stopped channel: $channelId")
                return
            }
            val channelMessage = try {
                WechatMessageConverter.toChannelMessage(msg, holder.channel)
            } catch (e: Exception) {
                logger.error("Failed to convert WeChat message for channel $channelId: ${e.message}", e)
                continue
            }
            tracker.markMessageReceived(channelId)
            turnExecutor.launchTurn(channelId, channelMessage.sessionId) {
                try {
                    handler(channelMessage)
                } catch (e: Exception) {
                    logger.error("Error handling WeChat message for channel $channelId: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Parse stored WeChat login credentials from the channel configJson.
     * Returns a [LoginContext] when a non-blank botToken is present (produced by
     * the admin-side scan flow), or null when no credentials are configured yet.
     */
    private fun parseCredentials(configJson: String?): LoginContext? {
        if (configJson.isNullOrBlank()) return null
        return try {
            @Suppress("UNCHECKED_CAST")
            val map = objectMapper.readValue(configJson, Map::class.java) as Map<String, Any?>
            val botToken = map["botToken"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val userId = map["userId"]?.toString().orEmpty()
            val botId = map["botId"]?.toString().orEmpty()
            val baseUrl = map["baseUrl"]?.toString().orEmpty()
            LoginContext(botToken, userId, botId, baseUrl)
        } catch (e: Exception) {
            logger.warn("Failed to parse WeChat credentials from configJson: {}", e.message)
            null
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

    companion object {
        // Upper bound for the interactive QR login wait. Without it a never-scanned
        // QR code parks the login thread (and the channel in CONNECTING) forever.
        private const val LOGIN_WAIT_MINUTES = 5L

        private const val STOP_JOIN_MS = 3_000L
    }
}
