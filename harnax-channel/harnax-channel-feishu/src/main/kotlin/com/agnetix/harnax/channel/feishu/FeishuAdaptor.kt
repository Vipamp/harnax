package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.channel.feishu.client.PlatformHttpClient
import com.agnetix.harnax.channel.feishu.client.PlatformResponse
import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.ChannelCommunicationMode
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.error.ChannelSendException
import com.agnetix.harnax.channel.sdk.message.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 飞书适配器
 * 处理飞书机器人的回调消息
 *
 * 支持两种通信模式：
 * - Webhook 模式：HTTP 回调，需要公网 IP 或域名
 * - WebSocket 模式：长连接，无需公网，适用于内网环境
 *
 * 本模块实现 SDK 的 ChannelAdaptor 接口，
 * 使用 ChannelRequest 替代 HttpServletRequest，与 Servlet 框架解耦。
 */
class FeishuAdaptor(
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
) : ChannelAdaptor {

    private val logger = LoggerFactory.getLogger(FeishuAdaptor::class.java)
    private val objectMapper = ObjectMapper().registerKotlinModule()

    // WebSocket 通信模式实例
    private val webSocketMode: FeishuWebSocketMode = FeishuWebSocketMode(httpClient)

    override fun getType(): ChannelType = ChannelType.FEISHU

    /**
     * 验证回调签名
     * 使用平台无关的 ChannelRequest 替代 HttpServletRequest
     *
     * 飞书签名验证逻辑：
     * 签名内容 = timestamp + nonce + appSecret + body
     * 签名算法 = HmacSHA256(appSecret, 签名内容)
     */
    override fun verifySignature(request: ChannelRequest, channel: ChannelSpec): Boolean {
        val signature = request.headers["X-Lark-Signature"] ?: return false
        val timestamp = request.headers["X-Lark-Request-Timestamp"] ?: return false
        val nonce = request.headers["X-Lark-Request-Nonce"] ?: return false

        val appSecret = channel.appSecret ?: return false
        val contentToSign = timestamp + nonce + appSecret + request.body
        val computedSignature = hmacSha256(appSecret, contentToSign)

        return signature.equals(computedSignature, ignoreCase = true)
    }

    /**
     * 解析消息
     * 使用平台无关的 ChannelRequest 替代 HttpServletRequest
     */
    override fun parseMessage(request: ChannelRequest): ChannelMessage {
        val body = request.body
        logger.debug("Received Feishu callback: {}", body)

        return try {
            val feishuEvent = objectMapper.readValue(body, FeishuEvent::class.java)
            val message = feishuEvent.event

            ChannelMessage.builder()
                .messageId(message?.messageId)
                .sessionId(message?.openChatId ?: message?.openId ?: "")
                .messageType(mapMessageType(message?.messageType))
                .role(MessageRole.USER)
                .content(message?.content?.let { extractTextContent(it) } ?: "")
                .channelType(ChannelType.FEISHU)
                .senderId(message?.openId)
                .senderName(message?.sender?.senderId?.id)
                .isGroupMessage(!message?.openChatId.isNullOrBlank())
                .groupId(message?.openChatId)
                .rawContent(feishuEvent)
                .timestamp(feishuEvent.ts ?: System.currentTimeMillis())
                .build()
        } catch (e: Exception) {
            logger.error("Failed to parse Feishu message", e)
            ChannelMessage.builder()
                .sessionId("")
                .content("")
                .channelType(ChannelType.FEISHU)
                .build()
        }
    }

    override fun buildResponse(reply: String, originalMessage: ChannelMessage): Any {
        return mapOf(
            "code" to 0,
            "msg" to "success",
            "data" to mapOf(
                "content" to reply,
            ),
        )
    }

    /**
     * 推送消息到平台
     * 根据通信模式自动选择发送方式
     */
    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        getMode(channel).sendMessage(channel, sessionId, message)
    }

    /**
     * 发送富消息
     */
    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        getMode(channel).sendRichMessage(channel, sessionId, richMessage)
    }

    /**
     * 处理 URL 验证请求
     * 飞书首次配置 Webhook 时会发送验证请求
     */
    override fun handleUrlVerification(request: ChannelRequest, channel: ChannelSpec): Any? {
        return try {
            val event = objectMapper.readValue(request.body, FeishuEvent::class.java)
            if (event.type == "url_verification") {
                mapOf("challenge" to (event.challenge ?: ""))
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 根据 channel 配置获取当前使用的通信模式
     */
    private fun getMode(channel: ChannelSpec): ChannelCommunicationMode = when (channel.communicationMode) {
        "websocket" -> webSocketMode
        else -> {
            // Webhook 模式：直接使用 httpClient 发送
            // Webhook 模式下，消息接收由外部 Controller 处理
            // 这里只处理发送逻辑
            object : ChannelCommunicationMode {
                override fun getModeName() = "webhook"
                override fun isCallbackMode() = true
                override fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
                    logger.info("Webhook mode for channel ${channel.id} - no startup needed")
                }
                override fun stop(channel: ChannelSpec) {
                    logger.info("Webhook mode for channel ${channel.id} - no cleanup needed")
                }
                override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
                    val webhookUrl = channel.webhookUrl
                    if (webhookUrl.isNullOrBlank()) {
                        throw ChannelSendException(
                            channelType = ChannelType.FEISHU,
                            platformErrorCode = null,
                            message = "Feishu webhook URL is not configured",
                        )
                    }
                    val messageBody = FeishuMessageBuilder.buildText(message)
                    val response = httpClient.postJson(webhookUrl, messageBody)
                    handleSendResponse(response)
                }
                override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
                    val webhookUrl = channel.webhookUrl
                    if (webhookUrl.isNullOrBlank()) {
                        throw ChannelSendException(
                            channelType = ChannelType.FEISHU,
                            platformErrorCode = null,
                            message = "Feishu webhook URL is not configured",
                        )
                    }
                    val messageBody = FeishuMessageBuilder.buildFromRichMessage(richMessage)
                    val response = httpClient.postJson(webhookUrl, messageBody)
                    handleSendResponse(response)
                }
            }
        }
    }

    /**
     * 启动通道（用于 WebSocket 模式）
     */
    fun startChannel(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        getMode(channel).start(channel, messageHandler)
    }

    /**
     * 停止通道（用于 WebSocket 模式）
     */
    fun stopChannel(channel: ChannelSpec) {
        getMode(channel).stop(channel)
    }

    /**
     * 处理发送响应
     */
    private fun handleSendResponse(response: PlatformResponse) {
        when (response) {
            is PlatformResponse.Success -> {
                try {
                    val json = objectMapper.readTree(response.body)
                    val statusCode = json.path("StatusCode").asInt(-1)
                    val statusMessage = json.path("StatusMessage").asText("unknown")

                    if (statusCode == 0) {
                        logger.info("Feishu message sent successfully")
                    } else {
                        throw ChannelSendException(
                            channelType = ChannelType.FEISHU,
                            platformErrorCode = statusCode.toString(),
                            message = "Feishu send failed: $statusMessage",
                        )
                    }
                } catch (e: ChannelSendException) {
                    throw e
                } catch (e: Exception) {
                    throw ChannelSendException(
                        channelType = ChannelType.FEISHU,
                        platformErrorCode = null,
                        message = "Failed to parse Feishu response: ${e.message}",
                        cause = e,
                    )
                }
            }
            is PlatformResponse.Error -> {
                throw ChannelSendException(
                    channelType = ChannelType.FEISHU,
                    platformErrorCode = response.platformCode,
                    message = "Feishu HTTP error: ${response.statusCode} - ${response.platformMessage ?: response.body}",
                    cause = response.exception,
                )
            }
        }
    }

    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val digest = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun extractTextContent(content: String?): String {
        if (content.isNullOrBlank()) return ""
        return try {
            val contentMap = objectMapper.readValue(content, Map::class.java)
            contentMap["text"] as? String ?: content
        } catch (e: Exception) {
            content
        }
    }

    private fun mapMessageType(msgType: String?): MessageType = when (msgType?.lowercase()) {
        "text" -> MessageType.TEXT
        "image" -> MessageType.IMAGE
        "file" -> MessageType.FILE
        "event" -> MessageType.EVENT
        else -> MessageType.TEXT
    }
}

/**
 * 飞书事件格式
 */
data class FeishuEvent(
    val ts: Long? = null,
    val uuid: String? = null,
    val token: String? = null,
    val type: String? = null,
    val challenge: String? = null,
    val event: FeishuMessage? = null,
)

/**
 * 飞书消息格式
 */
data class FeishuMessage(
    val messageId: String? = null,
    val openId: String? = null,
    val openChatId: String? = null,
    val messageType: String? = null,
    val content: String? = null,
    val createTime: Long? = null,
    val sender: FeishuSender? = null,
)

/**
 * 飞书发送者信息
 */
data class FeishuSender(
    val senderId: FeishuSenderId? = null,
    val senderType: String? = null,
)

data class FeishuSenderId(
    val id: String? = null,
    val unionId: String? = null,
    val openId: String? = null,
)
