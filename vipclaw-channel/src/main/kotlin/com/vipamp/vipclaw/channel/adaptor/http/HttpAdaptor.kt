package com.vipamp.vipclaw.channel.adaptor.http

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

/**
 * HTTP 通用适配器
 * 提供通用的 HTTP RESTful API 接口进行 AI 交互
 */
class HttpAdaptor : ChannelAdaptor {
    
    private val logger = LoggerFactory.getLogger(HttpAdaptor::class.java)
    private val objectMapper = ObjectMapper().registerKotlinModule()
    
    override fun getType(): ChannelType = ChannelType.HTTP
    
    override fun verifySignature(request: HttpServletRequest, channel: ChannelSpec): Boolean {
        // HTTP 接口通过 Token 验证
        val authHeader = request.getHeader("Authorization")
        val token = channel.token
        
        if (token.isNullOrBlank()) {
            // 如果没有配置 token，则跳过验证
            return true
        }
        
        // 支持 Bearer Token 格式
        val providedToken = if (authHeader?.startsWith("Bearer ", ignoreCase = true) == true) {
            authHeader.substring(7)
        } else {
            request.getHeader("X-Channel-Token") ?: request.getParameter("token")
        }
        
        return token == providedToken
    }
    
    override fun parseMessage(request: HttpServletRequest): ChannelMessage {
        val wrappedRequest = if (request is ContentCachingRequestWrapper) {
            request
        } else {
            ContentCachingRequestWrapper(request)
        }
        
        val body = wrappedRequest.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        logger.debug("Received HTTP callback: {}", body)
        
        return try {
            val httpRequest = objectMapper.readValue(body, HttpChannelRequest::class.java)
            
            ChannelMessage.builder()
                .messageId(httpRequest.messageId)
                .sessionId(httpRequest.sessionId ?: "default")
                .messageType(mapMessageType(httpRequest.messageType))
                .role(MessageRole.USER)
                .content(httpRequest.content ?: "")
                .channelType(ChannelType.HTTP)
                .senderId(httpRequest.userId)
                .senderName(httpRequest.userName)
                .rawContent(httpRequest)
                .timestamp(httpRequest.timestamp ?: System.currentTimeMillis())
                .build()
        } catch (e: Exception) {
            logger.error("Failed to parse HTTP message", e)
            ChannelMessage.builder()
                .sessionId("default")
                .content("")
                .channelType(ChannelType.HTTP)
                .build()
        }
    }
    
    override fun buildResponse(reply: String, originalMessage: ChannelMessage): Any {
        return HttpChannelResponse(
            code = 0,
            message = "success",
            data = ResponseData(
                content = reply,
                sessionId = originalMessage.sessionId,
                timestamp = System.currentTimeMillis()
            )
        )
    }
    
    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        // HTTP 通道通常不需要主动推送消息
        // 如果配置了 webhook_url，可以在这里实现推送逻辑
        val webhookUrl = channel.webhookUrl
        if (!webhookUrl.isNullOrBlank()) {
            logger.info("Sending message to HTTP webhook: $webhookUrl")
            // TODO: 实现 webhook 推送
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
 * HTTP Channel 请求格式
 */
data class HttpChannelRequest(
    val messageId: String? = null,
    val sessionId: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val messageType: String? = null,
    val content: String? = null,
    val timestamp: Long? = null
)

/**
 * HTTP Channel 响应格式
 */
data class HttpChannelResponse(
    val code: Int,
    val message: String,
    val data: ResponseData?
)

data class ResponseData(
    val content: String,
    val sessionId: String,
    val timestamp: Long
)
