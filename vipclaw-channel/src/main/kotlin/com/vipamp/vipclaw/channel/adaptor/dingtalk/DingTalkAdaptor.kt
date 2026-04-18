package com.vipamp.vipclaw.channel.adaptor.dingtalk

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
 * 钉钉适配器
 * 处理钉钉机器人的回调消息
 */
class DingTalkAdaptor : ChannelAdaptor {
    
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
                .isGroupMessage(!dingTalkMessage.conversationId.isNullOrBlank() && 
                    dingTalkMessage.conversationType == "2")
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
                "content" to reply
            )
        )
    }
    
    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        val webhookUrl = channel.webhookUrl ?: return
        // TODO: 实现钉钉消息推送
        logger.info("Sending message to DingTalk webhook: $webhookUrl")
    }
    
    private fun hmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val digest = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    private fun mapMessageType(msgType: String?): MessageType {
        return when (msgType?.lowercase()) {
            "text" -> MessageType.TEXT
            "picture" -> MessageType.IMAGE
            "file" -> MessageType.FILE
            "event" -> MessageType.EVENT
            else -> MessageType.TEXT
        }
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
    val conversationType: String? = null,  // 1: 单聊, 2: 群聊
    val senderId: String? = null,
    val senderNick: String? = null,
    val senderCorpId: String? = null,
    val senderDing: String? = null,
    val atUsers: List<DingTalkAtUser>? = null,
    val createAt: Long? = null
)

/**
 * 钉钉消息内容
 */
data class DingTalkContent(
    val content: String? = null,
    val text: String? = null
)

/**
 * 钉钉 @ 用户
 */
data class DingTalkAtUser(
    val staffId: String? = null,
    val dingTalkId: String? = null
)
