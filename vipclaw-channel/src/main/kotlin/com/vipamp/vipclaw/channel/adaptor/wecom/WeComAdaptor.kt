package com.vipamp.vipclaw.channel.adaptor.wecom

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.vipamp.vipclaw.channel.ChannelSpec
import com.vipamp.vipclaw.channel.ChannelType
import com.vipamp.vipclaw.channel.adaptor.ChannelAdaptor
import com.vipamp.vipclaw.channel.client.PlatformHttpClient
import com.vipamp.vipclaw.channel.client.PlatformResponse
import com.vipamp.vipclaw.channel.error.ChannelSendException
import com.vipamp.vipclaw.channel.message.*
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.web.util.ContentCachingRequestWrapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 企业微信适配器
 * 处理企业微信机器人的回调消息
 */
class WeComAdaptor(
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
) : ChannelAdaptor {

    private val logger = LoggerFactory.getLogger(WeComAdaptor::class.java)
    private val xmlMapper = XmlMapper.builder()
        .defaultUseWrapper(false)
        .build()
        .registerKotlinModule()
    private val objectMapper = ObjectMapper().registerKotlinModule()

    override fun getType(): ChannelType = ChannelType.WECOM

    override fun verifySignature(request: HttpServletRequest, channel: ChannelSpec): Boolean {
        val signature = request.getParameter("msg_signature")
            ?: request.getParameter("signature") ?: return false
        val timestamp = request.getParameter("timestamp") ?: return false
        val nonce = request.getParameter("nonce") ?: return false
        val echostr = request.getParameter("echostr")

        // 如果是首次验证 URL
        if (echostr != null) {
            return verifyUrlSignature(signature, timestamp, nonce, echostr, channel.token ?: "")
        }

        // 消息签名验证
        val token = channel.token ?: return false
        val sortedStr = listOf(token, timestamp, nonce).sorted().joinToString("")
        val computedSignature = sha1(sortedStr)
        return computedSignature == signature
    }

    override fun parseMessage(request: HttpServletRequest): ChannelMessage {
        val wrappedRequest = if (request is ContentCachingRequestWrapper) {
            request
        } else {
            ContentCachingRequestWrapper(request)
        }

        val body = wrappedRequest.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        logger.debug("Received WeCom callback: {}", body)

        return try {
            val weComMessage = xmlMapper.readValue(body, WeComMessage::class.java)
            ChannelMessage.builder()
                .messageId(weComMessage.MsgId?.toString())
                .sessionId(weComMessage.FromUserName ?: "")
                .messageType(mapMessageType(weComMessage.MsgType))
                .role(MessageRole.USER)
                .content(weComMessage.Content ?: "")
                .channelType(ChannelType.WECOM)
                .senderId(weComMessage.FromUserName)
                .isGroupMessage(weComMessage.FromUserName?.startsWith("@@") == true)
                .rawContent(weComMessage)
                .timestamp(weComMessage.CreateTime ?: System.currentTimeMillis())
                .build()
        } catch (e: Exception) {
            logger.error("Failed to parse WeCom message", e)
            ChannelMessage.builder()
                .sessionId("")
                .content("")
                .channelType(ChannelType.WECOM)
                .build()
        }
    }

    override fun buildResponse(reply: String, originalMessage: ChannelMessage): Any {
        val response = WeComResponse(
            ToUserName = originalMessage.senderId ?: "",
            FromUserName = originalMessage.rawContent?.let {
                (it as? WeComMessage)?.ToUserName
            } ?: "",
            CreateTime = System.currentTimeMillis() / 1000,
            MsgType = "text",
            Content = reply,
        )
        return xmlMapper.writeValueAsString(response)
    }

    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        val webhookUrl = channel.webhookUrl
        if (webhookUrl.isNullOrBlank()) {
            throw ChannelSendException(
                channelType = ChannelType.WECOM,
                platformErrorCode = null,
                message = "WeCom webhook URL is not configured",
            )
        }

        logger.info("Sending message to WeCom webhook: $webhookUrl")

        // 构建文本消息
        val messageBody = WeComMessageBuilder.buildText(message)

        // 发送消息
        val response = httpClient.postJson(webhookUrl, messageBody)

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
                channelType = ChannelType.WECOM,
                platformErrorCode = null,
                message = "WeCom webhook URL is not configured",
            )
        }

        logger.info("Sending rich message to WeCom webhook: $webhookUrl")

        // 构建富消息
        val messageBody = WeComMessageBuilder.buildFromRichMessage(richMessage)

        // 发送消息
        val response = httpClient.postJson(webhookUrl, messageBody)

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
                        logger.info("WeCom message sent successfully")
                    } else {
                        throw ChannelSendException(
                            channelType = ChannelType.WECOM,
                            platformErrorCode = errcode.toString(),
                            message = "WeCom send failed: $errmsg",
                        )
                    }
                } catch (e: ChannelSendException) {
                    throw e
                } catch (e: Exception) {
                    throw ChannelSendException(
                        channelType = ChannelType.WECOM,
                        platformErrorCode = null,
                        message = "Failed to parse WeCom response: ${e.message}",
                        cause = e,
                    )
                }
            }
            is PlatformResponse.Error -> {
                throw ChannelSendException(
                    channelType = ChannelType.WECOM,
                    platformErrorCode = response.platformCode,
                    message = "WeCom HTTP error: ${response.statusCode} - ${response.platformMessage ?: response.body}",
                    cause = response.exception,
                )
            }
        }
    }

    override fun handleUrlVerification(request: HttpServletRequest, channel: ChannelSpec): Any? {
        val echostr = request.getParameter("echostr") ?: return null
        val signature = request.getParameter("msg_signature") ?: return null
        val timestamp = request.getParameter("timestamp") ?: return null
        val nonce = request.getParameter("nonce") ?: return null

        return if (verifySignature(request, channel)) {
            echostr
        } else {
            null
        }
    }

    private fun verifyUrlSignature(
        signature: String,
        timestamp: String,
        nonce: String,
        echostr: String,
        token: String,
    ): Boolean {
        val sortedStr = listOf(token, timestamp, nonce, echostr).sorted().joinToString("")
        val computedSignature = sha1(sortedStr)
        return computedSignature == signature
    }

    private fun sha1(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
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
 * 企业微信消息格式
 */
data class WeComMessage(
    val ToUserName: String? = null,
    val FromUserName: String? = null,
    val CreateTime: Long? = null,
    val MsgType: String? = null,
    val Content: String? = null,
    val MsgId: Long? = null,
    val AgentID: String? = null,
)

/**
 * 企业微信响应格式
 */
data class WeComResponse(
    val ToUserName: String,
    val FromUserName: String,
    val CreateTime: Long,
    val MsgType: String,
    val Content: String,
)
