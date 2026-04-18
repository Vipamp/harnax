package com.vipamp.vipclaw.channel.adaptor.feishu

import com.vipamp.vipclaw.channel.ChannelSpec
import com.vipamp.vipclaw.channel.ChannelType
import com.vipamp.vipclaw.channel.adaptor.ChannelAdaptor
import com.vipamp.vipclaw.channel.message.ChannelMessage
import com.vipamp.vipclaw.channel.message.MessageType
import com.vipamp.vipclaw.channel.message.MessageRole
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.web.util.ContentCachingRequestWrapper
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 飞书适配器
 * 处理飞书机器人的回调消息
 */
class FeishuAdaptor : ChannelAdaptor {
    
    private val logger = LoggerFactory.getLogger(FeishuAdaptor::class.java)
    private val objectMapper = ObjectMapper().registerKotlinModule()
    
    override fun getType(): ChannelType = ChannelType.FEISHU
    
    override fun verifySignature(request: HttpServletRequest, channel: ChannelSpec): Boolean {
        val signature = request.getHeader("X-Lark-Signature") ?: return false
        val timestamp = request.getHeader("X-Lark-Request-Timestamp") ?: return false
        val nonce = request.getHeader("X-Lark-Request-Nonce") ?: return false
        
        // 读取请求体
        val wrappedRequest = if (request is ContentCachingRequestWrapper) {
            request
        } else {
            ContentCachingRequestWrapper(request)
        }
        val body = wrappedRequest.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        
        // 飞书签名验证
        val appSecret = channel.appSecret ?: return false
        val contentToSign = timestamp + nonce + appSecret + body
        val computedSignature = hmacSha256(appSecret, contentToSign)
        
        return signature.equals(computedSignature, ignoreCase = true)
    }
    
    override fun parseMessage(request: HttpServletRequest): ChannelMessage {
        val wrappedRequest = if (request is ContentCachingRequestWrapper) {
            request
        } else {
            ContentCachingRequestWrapper(request)
        }
        
        val body = wrappedRequest.inputStream.readBytes().toString(StandardCharsets.UTF_8)
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
        // 飞书通常通过 webhook 主动推送回复，这里返回 JSON 格式
        return mapOf(
            "code" to 0,
            "msg" to "success",
            "data" to mapOf(
                "content" to reply
            )
        )
    }
    
    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        val webhookUrl = channel.webhookUrl ?: return
        // TODO: 实现飞书消息推送
        logger.info("Sending message to Feishu webhook: $webhookUrl")
    }
    
    override fun handleUrlVerification(request: HttpServletRequest, channel: ChannelSpec): Any? {
        val wrappedRequest = if (request is ContentCachingRequestWrapper) {
            request
        } else {
            ContentCachingRequestWrapper(request)
        }
        
        val body = wrappedRequest.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        
        return try {
            val event = objectMapper.readValue(body, FeishuEvent::class.java)
            // 飞书 URL 验证会返回 challenge
            if (event.type == "url_verification") {
                mapOf("challenge" to (event.challenge ?: ""))
            } else {
                null
            }
        } catch (e: Exception) {
            null
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
    
    private fun mapMessageType(msgType: String?): MessageType {
        return when (msgType?.lowercase()) {
            "text" -> MessageType.TEXT
            "image" -> MessageType.IMAGE
            "file" -> MessageType.FILE
            "event" -> MessageType.EVENT
            else -> MessageType.TEXT
        }
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
    val event: FeishuMessage? = null
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
    val sender: FeishuSender? = null
)

/**
 * 飞书发送者信息
 */
data class FeishuSender(
    val senderId: FeishuSenderId? = null,
    val senderType: String? = null
)

data class FeishuSenderId(
    val id: String? = null,
    val unionId: String? = null,
    val openId: String? = null
)
