package com.agnetix.harnax.channel.adaptor.dingtalk

import com.agnetix.harnax.channel.ChannelSpec
import com.agnetix.harnax.channel.ChannelType
import com.agnetix.harnax.channel.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.client.PlatformHttpClient
import com.agnetix.harnax.channel.client.PlatformResponse
import com.agnetix.harnax.channel.error.ChannelSendException
import com.agnetix.harnax.channel.message.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.web.util.ContentCachingRequestWrapper
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 钉钉适配器
 * 处理钉钉机器人的回调消息
 */
class DingTalkAdaptor(
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
) : ChannelAdaptor {

    private val logger = LoggerFactory.getLogger(DingTalkAdaptor::class.java)
    private val objectMapper = ObjectMapper().registerKotlinModule()

    override fun getType(): ChannelType = ChannelType.DINGTALK

    override fun verifySignature(request: HttpServletRequest, channel: ChannelSpec): Boolean {
        val timestamp = request.getHeader("timestamp") ?: return false
        val sign = request.getHeader("sign") ?: return false

        // 钉钉签名验证
        val appSecret = channel.appSecret ?: return false
        val stringToSign = timestamp + "\n" + appSecret
        val computedSign = hmacSha256(appSecret, stringToSign)

        return sign == computedSign
    }

    override fun parseMessage(request: HttpServletRequest): ChannelMessage {
        val wrappedRequest = if (request is ContentCachingRequestWrapper) {
            request
        } else {
            ContentCachingRequestWrapper(request)
        }

        val body = wrappedRequest.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        logger.debug("Received DingTalk callback: {}", body)

        return try {
            val dingTalkMessage = objectMapper.readValue(body, DingTalkMessage::class.java)

            ChannelMessage.builder()
                .messageId(dingTalkMessage.msgId)
                .sessionId(dingTalkMessage.conversationId ?: dingTalkMessage.senderId ?: "")
                .messageType(mapMessageType(dingTalkMessage.msgType))
                .role(MessageRole.USER)
                .content(dingTalkMessage.content?.content ?: dingTalkMessage.content?.text ?: "")
                .channelType(ChannelType.DINGTALK)
                .senderId(dingTalkMessage.senderId)
                .senderName(dingTalkMessage.senderNick)
                .isGroupMessage(
                    !dingTalkMessage.conversationId.isNullOrBlank() &&
                        dingTalkMessage.conversationType == "2",
                )
                .groupId(dingTalkMessage.conversationId)
                .atUserIds(dingTalkMessage.atUsers?.mapNotNull { it.staffId } ?: emptyList())
                .rawContent(dingTalkMessage)
                .timestamp(dingTalkMessage.createAt ?: System.currentTimeMillis())
                .build()
        } catch (e: Exception) {
            logger.error("Failed to parse DingTalk message", e)
            ChannelMessage.builder()
                .sessionId("")
                .content("")
                .channelType(ChannelType.DINGTALK)
                .build()
        }
    }

    override fun buildResponse(reply: String, originalMessage: ChannelMessage): Any {
        // 钉钉响应格式
        return mapOf(
            "msgtype" to "text",
            "text" to mapOf(
                "content" to reply,
            ),
        )
    }

    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        val webhookUrl = channel.webhookUrl
        if (webhookUrl.isNullOrBlank()) {
            throw ChannelSendException(
                channelType = ChannelType.DINGTALK,
                platformErrorCode = null,
                message = "DingTalk webhook URL is not configured",
            )
        }

        logger.info("Sending message to DingTalk webhook: $webhookUrl")

        // 构建文本消息
        val messageBody = DingTalkMessageBuilder.buildText(message)

        // 发送消息（钉钉需要签名）
        val appSecret = channel.appSecret ?: ""
        val response = httpClient.postWithSign(webhookUrl, messageBody, appSecret)

        // 处理响应
        handleSendResponse(response)
    }

    /**
     * 发送富消息
     */
    suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        val webhookUrl = channel.webhookUrl
        if (webhookUrl.isNullOrBlank()) {
            throw ChannelSendException(
                channelType = ChannelType.DINGTALK,
                platformErrorCode = null,
                message = "DingTalk webhook URL is not configured",
            )
        }

        logger.info("Sending rich message to DingTalk webhook: $webhookUrl")

        // 构建富消息
        val messageBody = DingTalkMessageBuilder.buildFromRichMessage(richMessage)

        // 发送消息（钉钉需要签名）
        val appSecret = channel.appSecret ?: ""
        val response = httpClient.postWithSign(webhookUrl, messageBody, appSecret)

        // 处理响应
        handleSendResponse(response)
    }

    /**
     * 处理发送响应
     */
    private fun handleSendResponse(response: PlatformResponse) {
        when (response) {
            is PlatformResponse.Success -> {
                try {
                    val json = objectMapper.readTree(response.body)
                    val errcode = json.path("errcode").asInt(-1)
                    val errmsg = json.path("errmsg").asText("unknown")

                    if (errcode == 0) {
                        logger.info("DingTalk message sent successfully")
                    } else {
                        throw ChannelSendException(
                            channelType = ChannelType.DINGTALK,
                            platformErrorCode = errcode.toString(),
                            message = "DingTalk send failed: $errmsg",
                        )
                    }
                } catch (e: ChannelSendException) {
                    throw e
                } catch (e: Exception) {
                    throw ChannelSendException(
                        channelType = ChannelType.DINGTALK,
                        platformErrorCode = null,
                        message = "Failed to parse DingTalk response: ${e.message}",
                        cause = e,
                    )
                }
            }
            is PlatformResponse.Error -> {
                throw ChannelSendException(
                    channelType = ChannelType.DINGTALK,
                    platformErrorCode = response.platformCode,
                    message = "DingTalk HTTP error: ${response.statusCode} - ${response.platformMessage ?: response.body}",
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

    private fun mapMessageType(msgType: String?): MessageType = when (msgType?.lowercase()) {
        "text" -> MessageType.TEXT
        "picture" -> MessageType.IMAGE
        "file" -> MessageType.FILE
        "event" -> MessageType.EVENT
        else -> MessageType.TEXT
    }
}

/**
 * 钉钉消息格式
 */
data class DingTalkMessage(
    val msgId: String? = null,
    val msgType: String? = null,
    val content: DingTalkContent? = null,
    val conversationId: String? = null,
    val conversationType: String? = null, // 1: 单聊, 2: 群聊
    val senderId: String? = null,
    val senderNick: String? = null,
    val senderCorpId: String? = null,
    val senderDing: String? = null,
    val atUsers: List<DingTalkAtUser>? = null,
    val createAt: Long? = null,
)

/**
 * 钉钉消息内容
 */
data class DingTalkContent(
    val content: String? = null,
    val text: String? = null,
)

/**
 * 钉钉 @ 用户
 */
data class DingTalkAtUser(
    val staffId: String? = null,
    val dingTalkId: String? = null,
)
