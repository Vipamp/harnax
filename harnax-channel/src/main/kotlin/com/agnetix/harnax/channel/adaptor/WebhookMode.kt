package com.agnetix.harnax.channel.adaptor

import com.agnetix.harnax.channel.ChannelSpec
import com.agnetix.harnax.channel.client.PlatformHttpClient
import com.agnetix.harnax.channel.error.ChannelSignatureException
import com.agnetix.harnax.channel.message.ChannelMessage
import com.agnetix.harnax.channel.message.RichMessage
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Webhook 通信模式实现
 * 基于 HTTP 回调的传统模式，需要公网 IP 或域名
 *
 * 特点：
 * - 平台通过 HTTP POST 推送事件到开发者服务器
 * - 需要验签和解析 HTTP 请求
 * - 发送消息通过平台 Webhook URL 或 Open API
 */
class WebhookMode(
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
    private val messageParser: (HttpServletRequest) -> ChannelMessage,
    private val signatureVerifier: (HttpServletRequest, ChannelSpec) -> Boolean,
    private val urlVerificationHandler: ((HttpServletRequest, ChannelSpec) -> Any?)? = null,
    private val messageSender: suspend (ChannelSpec, String, String) -> Unit,
    private val richMessageSender: suspend (ChannelSpec, String, RichMessage) -> Unit,
) : ChannelCommunicationMode {

    private val logger = LoggerFactory.getLogger(WebhookMode::class.java)

    override fun getModeName(): String = "webhook"

    override fun isCallbackMode(): Boolean = true

    /**
     * Webhook 模式不需要主动启动
     * 由外部 Servlet 容器接收 HTTP 请求
     */
    override fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        logger.info("Webhook mode for channel ${channel.id} - no startup needed, waiting for HTTP callbacks")
    }

    /**
     * Webhook 模式无需停止
     */
    override fun stop(channel: ChannelSpec) {
        logger.info("Webhook mode for channel ${channel.id} - no cleanup needed")
    }

    /**
     * 处理 HTTP 回调请求
     * 供外部 Controller 调用
     *
     * @param request HTTP 请求
     * @param channel Channel 配置
     * @param messageHandler 消息处理函数
     * @return 响应对象（如 URL 验证响应）
     */
    fun handleRequest(
        request: HttpServletRequest,
        channel: ChannelSpec,
        messageHandler: suspend (ChannelMessage) -> Unit,
    ): Any? {
        logger.debug("Handling webhook request for channel: ${channel.id}")

        // 1. 处理 URL 验证请求（首次配置时的验证）
        urlVerificationHandler?.let { handler ->
            val verificationResponse = handler(request, channel)
            if (verificationResponse != null) {
                logger.info("URL verification successful for channel: ${channel.id}")
                return verificationResponse
            }
        }

        // 2. 验证签名
        if (!signatureVerifier(request, channel)) {
            throw ChannelSignatureException(
                channelType = channel.type,
                detail = "Signature verification failed",
            )
        }

        // 3. 解析消息
        val message = messageParser(request)
        logger.info("Parsed webhook message from channel ${channel.id}: sessionId=${message.sessionId}")

        // 4. 处理消息
        runBlocking {
            messageHandler(message)
        }

        return null
    }

    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        logger.info("Sending message via webhook for channel: ${channel.id}")
        messageSender(channel, sessionId, message)
    }

    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        logger.info("Sending rich message via webhook for channel: ${channel.id}")
        richMessageSender(channel, sessionId, richMessage)
    }
}
