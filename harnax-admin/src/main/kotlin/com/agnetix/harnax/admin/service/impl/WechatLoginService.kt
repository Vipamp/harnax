package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.mapper.ChannelMapper
import com.github.wechat.ilink.sdk.ILinkClient
import com.github.wechat.ilink.sdk.core.login.LoginStatus
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * WeChat (iLink) QR-code login service — admin side.
 *
 * Runs the interactive scan-login flow so that the resulting credentials
 * (botToken/userId/botId/baseUrl) can be persisted into the channel's
 * configJson. The channel-service then connects using these credentials
 * directly, without needing to scan again.
 *
 * Lifecycle: a temporary [ILinkClient] is created per channel when login
 * starts, kept in memory while the user scans, and closed once login
 * succeeds (after exporting credentials), or on cancel/expiry.
 */
@Service
class WechatLoginService(
    private val channelMapper: ChannelMapper,
) {
    private val log = LoggerFactory.getLogger(WechatLoginService::class.java)
    private val objectMapper = jacksonObjectMapper()

    /** channelId -> in-progress login (client + its timeout cleanup future). */
    private val loginClients = ConcurrentHashMap<Long, PendingLogin>()

    /** Fallback cleanup scheduler so abandoned logins (browser closed, no poll) don't leak. */
    private val cleanupScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "wechat-login-cleanup").apply { isDaemon = true }
        }

    private class PendingLogin(
        val client: ILinkClient,
        @Volatile var cleanupFuture: ScheduledFuture<*>? = null,
    )

    companion object {
        /** Max lifetime of an in-progress login before it is force-closed. */
        private const val LOGIN_TIMEOUT_MINUTES = 5L
    }

    /**
     * Remove and fully release the pending login for a channel: cancel its
     * timeout task and close the underlying client. Safe to call repeatedly.
     */
    private fun removeAndClose(channelId: Long, cancelLogin: Boolean = false) {
        loginClients.remove(channelId)?.let { pending ->
            pending.cleanupFuture?.cancel(false)
            if (cancelLogin) runCatching { pending.client.cancelLogin() }
            runCatching { pending.client.close() }
        }
    }

    /**
     * Start (or restart) a QR login for the given channel and return the QR
     * code as a base64-encoded PNG data URL for the frontend to render.
     */
    fun startLogin(channelId: Long): String {
        // Close any previous in-progress login for this channel.
        removeAndClose(channelId, cancelLogin = true)

        val client = ILinkClient.builder().build()
        val pending = PendingLogin(client)
        loginClients[channelId] = pending

        val qrContent = client.executeLogin()

        // Fallback cleanup: if the user abandons the flow (closes the browser and
        // never polls or cancels), force-close the client so it doesn't leak.
        pending.cleanupFuture = cleanupScheduler.schedule({
            if (loginClients[channelId] === pending) {
                log.info("WeChat login for channel {} timed out, cleaning up", channelId)
                removeAndClose(channelId, cancelLogin = true)
            }
        }, LOGIN_TIMEOUT_MINUTES, TimeUnit.MINUTES)

        log.info("WeChat login QR generated for channel {}", channelId)
        return toQrDataUrl(qrContent)
    }

    /**
     * Query the current login status. On success, export credentials, persist
     * them to the channel's configJson, close the temporary client, and report
     * "LOGGED_IN". Possible statuses: NOT_LOGIN/WAITING/SCANNED/LOGGED_IN/EXPIRED/ERROR.
     */
    fun queryStatus(channelId: Long): WechatLoginStatus {
        val pending = loginClients[channelId]
            ?: return WechatLoginStatus(status = "NOT_LOGIN", message = "No login in progress")
        val client = pending.client

        val loginStatus = client.loginStatus
        val status = loginStatus.status

        return when (status) {
            LoginStatus.Status.LOGGED_IN -> {
                val ctx = client.loginContext
                if (ctx == null) {
                    WechatLoginStatus(status = "ERROR", message = "Login context missing")
                } else {
                    persistCredentials(channelId, ctx.botToken, ctx.userId, ctx.botId, ctx.baseUrl)
                    removeAndClose(channelId)
                    log.info("WeChat login succeeded for channel {}, botId={}", channelId, ctx.botId)
                    WechatLoginStatus(status = "LOGGED_IN", message = "Login successful")
                }
            }
            LoginStatus.Status.EXPIRED -> {
                removeAndClose(channelId)
                WechatLoginStatus(status = "EXPIRED", message = "QR code expired, please refresh")
            }
            LoginStatus.Status.ERROR -> {
                val msg = loginStatus.errorMessage ?: "Login error"
                removeAndClose(channelId)
                WechatLoginStatus(status = "ERROR", message = msg)
            }
            LoginStatus.Status.SCANNED -> WechatLoginStatus(status = "SCANNED", message = "Scanned, waiting for confirmation")
            else -> WechatLoginStatus(status = "WAITING", message = "Waiting for scan")
        }
    }

    /**
     * Cancel an in-progress login and release the temporary client.
     */
    fun cancelLogin(channelId: Long) {
        removeAndClose(channelId, cancelLogin = true)
        log.info("WeChat login cancelled for channel {}", channelId)
    }

    /**
     * Release all in-progress logins and shut down the scheduler on shutdown.
     */
    @PreDestroy
    fun shutdown() {
        loginClients.keys.toList().forEach { removeAndClose(it, cancelLogin = true) }
        cleanupScheduler.shutdownNow()
    }

    /**
     * Merge the obtained credentials into the channel's configJson and refresh
     * update_time so the channel-service reconcile loop restarts the listener.
     */
    private fun persistCredentials(channelId: Long, botToken: String, userId: String, botId: String, baseUrl: String) {
        val channel = channelMapper.selectById(channelId)
            ?: throw IllegalStateException("Channel $channelId not found")

        val config: MutableMap<String, Any?> = try {
            if (channel.configJson.isNullOrBlank()) {
                mutableMapOf()
            } else {
                @Suppress("UNCHECKED_CAST")
                (objectMapper.readValue(channel.configJson, Map::class.java) as Map<String, Any?>).toMutableMap()
            }
        } catch (e: Exception) {
            log.warn("Existing configJson for channel {} is invalid, overwriting: {}", channelId, e.message)
            mutableMapOf()
        }

        config["botToken"] = botToken
        config["userId"] = userId
        config["botId"] = botId
        config["baseUrl"] = baseUrl

        channel.configJson = objectMapper.writeValueAsString(config)
        channel.updateTime = LocalDateTime.now()
        channelMapper.updateById(channel)
    }

    /**
     * Render QR text content as a base64 PNG data URL.
     */
    private fun toQrDataUrl(content: String, size: Int = 280): String {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        val base64 = Base64.getEncoder().encodeToString(baos.toByteArray())
        return "data:image/png;base64,$base64"
    }
}

/**
 * WeChat login status response.
 */
data class WechatLoginStatus(
    val status: String,
    val message: String,
)
