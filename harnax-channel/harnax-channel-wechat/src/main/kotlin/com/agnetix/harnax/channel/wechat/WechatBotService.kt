package com.agnetix.harnax.channel.wechat

import com.github.wechat.ilink.sdk.ILinkClient
import com.github.wechat.ilink.sdk.core.config.ILinkConfig
import com.github.wechat.ilink.sdk.core.context.ResumeContext
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener
import com.github.wechat.ilink.sdk.core.login.LoginContext
import com.github.wechat.ilink.sdk.core.model.WeixinMessage
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WeChat Bot Service
 * Encapsulates complete lifecycle management of ILinkClient
 *
 * Responsibilities:
 * - Manage ILinkClient creation, login, message sending/receiving, and shutdown
 * - Cache ILinkClient instances for each channel
 * - Provide message polling and callback mechanisms
 *
 * Usage:
 * ```
 * val service = WechatBotService()
 * val client = service.getOrCreateClient(channelSpec)
 * service.startPolling(channelSpec) { message -> ... }
 * ```
 */
class WechatBotService {

    private val logger = LoggerFactory.getLogger(WechatBotService::class.java)

    /**
     * Channel ID → ILinkClient instance cache
     */
    private val clientMap = ConcurrentHashMap<Long, ILinkClient>()

    /**
     * Channel ID → polling thread running flag
     */
    private val pollingFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    /**
     * Get or create ILinkClient instance
     *
     * @param channelId Channel ID
     * @param config iLink configuration (only used on first creation)
     * @return ILinkClient instance
     */
    fun getOrCreateClient(
        channelId: Long,
        config: ILinkConfig = ILinkConfig.builder().build(),
    ): ILinkClient = clientMap.getOrPut(channelId) {
        logger.info("Creating ILinkClient for channel $channelId")
        ILinkClient.builder()
            .config(config)
            .onLogin(object : OnLoginListener {
                override fun onLoginSuccess(context: LoginContext) {
                    logger.info("WeChat bot logged in successfully, botId = ${context.botId}")
                }

                override fun onLoginFailure(throwable: Throwable) {
                    logger.error("WeChat bot login failed: ${throwable.message}", throwable)
                }
            })
            .build()
    }

    /**
     * Build (or return cached) ILinkClient from previously obtained login credentials.
     *
     * Uses [ResumeContext] so the client connects directly with a stored
     * [LoginContext] (botToken/userId/botId/baseUrl) — no QR scan required.
     * The credentials are produced by the admin-side scan flow and persisted in
     * the channel's configJson.
     *
     * @param channelId Channel ID
     * @param loginContext Previously obtained login credentials
     * @param config iLink configuration (only used on first creation)
     * @return ILinkClient instance ready to poll
     */
    fun createClientFromCredentials(
        channelId: Long,
        loginContext: LoginContext,
        config: ILinkConfig = ILinkConfig.builder().build(),
    ): ILinkClient = clientMap.getOrPut(channelId) {
        logger.info("Creating ILinkClient from stored credentials for channel $channelId, botId=${loginContext.botId}")
        ILinkClient.builder()
            .config(config)
            .resumeContext(ResumeContext.of(loginContext))
            .onLogin(object : OnLoginListener {
                override fun onLoginSuccess(context: LoginContext) {
                    logger.info("WeChat bot resumed session successfully, botId = ${context.botId}")
                }

                override fun onLoginFailure(throwable: Throwable) {
                    logger.error("WeChat bot resume failed: ${throwable.message}", throwable)
                }
            })
            .build()
    }

    /**
     * Execute login flow
     *
     * @param channelId Channel ID
     * @return QR code content, needs to be rendered as QR code for user to scan
     */
    fun executeLogin(channelId: Long): String {
        val client = getOrCreateClient(channelId)
        val qrCodeContent = client.executeLogin()
        logger.info("QR code generated for channel $channelId, please scan to login")
        return qrCodeContent
    }

    /**
     * Asynchronously wait for login completion
     *
     * @param channelId Channel ID
     * @return CompletableFuture of login result
     */
    fun getLoginFuture(channelId: Long): CompletableFuture<LoginContext>? {
        val client = clientMap[channelId] ?: return null
        return client.loginFuture
    }

    /**
     * Check if logged in
     */
    fun isLoggedIn(channelId: Long): Boolean {
        val client = clientMap[channelId] ?: return false
        return client.isLoggedIn
    }

    /**
     * Start message polling
     *
     * Continuously calls getUpdates() in a separate thread to retrieve messages,
     * and passes messages to upper layer for processing via messageHandler callback.
     *
     * @param channelId Channel ID
     * @param messageHandler Message handler, receives WeixinMessage list
     */
    fun startPolling(
        channelId: Long,
        messageHandler: (List<WeixinMessage>) -> Unit,
    ) {
        val flag = AtomicBoolean(true)
        pollingFlags[channelId] = flag

        val client = clientMap[channelId]
            ?: throw IllegalStateException("ILinkClient not found for channel $channelId, please login first")

        val thread = Thread({
            logger.info("Starting message polling for channel $channelId")
            while (flag.get() && client.isLoggedIn) {
                try {
                    val messages = client.getUpdates()
                    if (messages.isNotEmpty()) {
                        messageHandler(messages)
                    }
                } catch (e: IOException) {
                    logger.error("Error polling messages for channel $channelId: ${e.message}", e)
                    try {
                        Thread.sleep(3000) // Brief wait after error before retry
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            logger.info("Message polling stopped for channel $channelId")
        }, "wechat-polling-$channelId")

        thread.isDaemon = true
        thread.start()
    }

    /**
     * Stop message polling
     */
    fun stopPolling(channelId: Long) {
        pollingFlags[channelId]?.set(false)
        logger.info("Stopping message polling for channel $channelId")
    }

    /**
     * Send text message
     *
     * @param channelId Channel ID
     * @param toUserId Target user ID (format: xxx@im.wechat)
     * @param text Message content
     */
    fun sendText(channelId: Long, toUserId: String, text: String) {
        val client = clientMap[channelId]
            ?: throw IllegalStateException("ILinkClient not found for channel $channelId")
        client.sendText(toUserId, text)
    }

    /**
     * Send text message with typing indicator
     *
     * @param channelId Channel ID
     * @param toUserId Target user ID
     * @param text Message content
     * @param typingMillis Typing indicator duration (milliseconds)
     */
    fun sendTextWithTyping(channelId: Long, toUserId: String, text: String, typingMillis: Long = 1500L) {
        val client = clientMap[channelId]
            ?: throw IllegalStateException("ILinkClient not found for channel $channelId")
        client.sendTextWithTyping(toUserId, text, typingMillis)
    }

    /**
     * Send image message
     */
    fun sendImage(channelId: Long, toUserId: String, imageBytes: ByteArray, fileName: String, caption: String) {
        val client = clientMap[channelId]
            ?: throw IllegalStateException("ILinkClient not found for channel $channelId")
        client.sendImage(toUserId, imageBytes, fileName, caption)
    }

    /**
     * Send file message
     */
    fun sendFile(channelId: Long, toUserId: String, fileBytes: ByteArray, fileName: String, caption: String) {
        val client = clientMap[channelId]
            ?: throw IllegalStateException("ILinkClient not found for channel $channelId")
        client.sendFile(toUserId, fileBytes, fileName, caption)
    }

    /**
     * Clear session context
     */
    fun clearContext(channelId: Long, userId: String) {
        val client = clientMap[channelId] ?: return
        client.clearContext(userId)
    }

    /**
     * Close client for specified channel
     */
    fun closeClient(channelId: Long) {
        stopPolling(channelId)
        clientMap.remove(channelId)?.close()
        logger.info("ILinkClient closed for channel $channelId")
    }

    /**
     * Close all clients
     */
    fun closeAll() {
        pollingFlags.keys.forEach { stopPolling(it) }
        clientMap.forEach { (id, client) ->
            client.close()
            logger.info("ILinkClient closed for channel $id")
        }
        clientMap.clear()
    }
}
