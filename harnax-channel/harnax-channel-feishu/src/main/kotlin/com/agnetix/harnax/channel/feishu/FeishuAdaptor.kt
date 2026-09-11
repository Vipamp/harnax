package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.channel.feishu.client.PlatformHttpClient
import com.agnetix.harnax.channel.feishu.client.PlatformResponse
import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.ChannelCommunicationMode
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.dispatch.ChannelTurnExecutor
import com.agnetix.harnax.channel.sdk.error.ChannelSendException
import com.agnetix.harnax.channel.sdk.message.*
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionState
import com.agnetix.harnax.channel.sdk.monitor.ChannelMetricsSink
import com.agnetix.harnax.channel.sdk.monitor.NoOpChannelMetricsSink
import com.agnetix.harnax.channel.sdk.service.ChannelChatService
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import org.slf4j.LoggerFactory
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Feishu Adapter
 * Handles callback messages from Feishu bot
 *
 * Supports two communication modes:
 * - Webhook mode: HTTP callback, requires public IP or domain
 * - WebSocket mode: Long connection, no public network needed, suitable for intranet environments
 *
 * This module implements the SDK's ChannelAdaptor interface,
 * using ChannelRequest instead of HttpServletRequest, decoupled from Servlet framework.
 */
class FeishuAdaptor(
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
    turnExecutor: ChannelTurnExecutor = ChannelTurnExecutor.SHARED,
    metricsSink: ChannelMetricsSink = NoOpChannelMetricsSink,
) : ChannelAdaptor {

    private val logger = LoggerFactory.getLogger(FeishuAdaptor::class.java)
    private val objectMapper = jacksonObjectMapper()

    // WebSocket communication mode instance
    private val webSocketMode: FeishuWebSocketMode = FeishuWebSocketMode(httpClient, turnExecutor, metricsSink)

    override fun getType(): ChannelType = ChannelType.FEISHU

    /**
     * Verify callback signature
     * Uses platform-agnostic ChannelRequest instead of HttpServletRequest
     *
     * Feishu signature verification logic:
     * Signature content = timestamp + nonce + appSecret + body
     * Signature algorithm = HmacSHA256(appSecret, signature content)
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
     * Parse message
     * Uses platform-agnostic ChannelRequest instead of HttpServletRequest
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

    override fun buildResponse(reply: String, originalMessage: ChannelMessage): Any = mapOf(
        "code" to 0,
        "msg" to "success",
        "data" to mapOf(
            "content" to reply,
        ),
    )

    /**
     * Push message to platform
     * Automatically selects sending method based on communication mode
     */
    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        getMode(channel).sendMessage(channel, sessionId, message)
    }

    /**
     * Send rich message
     */
    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        getMode(channel).sendRichMessage(channel, sessionId, richMessage)
    }

    /**
     * Feishu does not support streaming output
     *
     * Feishu Open API does not support partial message updates,
     * messages must be sent as complete content.
     */
    override fun supportsStreamingOutput(): Boolean = false

    /**
     * Send typing indicator
     *
     * Feishu API does not currently support a typing indicator,
     * this is a no-op implementation that can be extended in the future
     * if Feishu adds such API support.
     */
    override suspend fun sendTypingIndicator(channel: ChannelSpec, sessionId: String) {
        // Feishu API does not support typing indicator yet, no-op
        logger.debug("Typing indicator not supported by Feishu, skipping for session $sessionId")
    }

    /**
     * Handle URL verification request
     * Feishu sends a verification request when configuring Webhook for the first time
     */
    override fun handleUrlVerification(request: ChannelRequest, channel: ChannelSpec): Any? = try {
        val event = objectMapper.readValue(request.body, FeishuEvent::class.java)
        if (event.type == "url_verification") {
            mapOf("challenge" to (event.challenge ?: ""))
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Get the current communication mode based on channel configuration
     */
    private fun getMode(channel: ChannelSpec): ChannelCommunicationMode = when (channel.communicationMode) {
        "websocket" -> webSocketMode
        else -> webhookMode
    }

    /**
     * Webhook mode: message receiving is handled by the external Controller,
     * so this only carries the sending logic.
     */
    private val webhookMode: ChannelCommunicationMode = object : ChannelCommunicationMode {
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

    /**
     * Start channel (for WebSocket mode)
     *
     * @return true when an active listener was established, false for callback (webhook) mode
     */
    fun startChannel(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit): Boolean {
        val mode = getMode(channel)
        if (mode.isCallbackMode()) {
            logger.info("Feishu channel ${channel.id} is in callback mode, no listener to start")
            return false
        }
        mode.start(channel, messageHandler)
        return true
    }

    /**
     * Start Feishu channel with AI Agent integration
     *
     * Convenience method that creates ChannelChatService internally and
     * automatically handles the complete message processing flow:
     * 1. Start WebSocket/Webhook communication mode
     * 2. Each incoming message is processed by ChannelChatService.chat():
     *    - Save user message to session
     *    - Call AgentAdaptor for AI processing
     *    - Determine output strategy (batch for Feishu)
     *    - Send AI reply via Feishu
     *    - Save AI reply to session
     *
     * Feishu uses batch mode (supportsStreamingOutput=false):
     * - Messages are sent as complete content after AI processing finishes
     *
     * @param channel Channel configuration
     * @param agentAdaptor AI Agent processor
     * @param sessionManager Session manager for conversation history
     */
    override fun startChannelWithAgent(
        channel: ChannelSpec,
        agentAdaptor: AgentAdaptor,
        sessionManager: ChannelSessionManager,
        chatService: ChannelChatService?,
    ): Boolean {
        val effectiveChatService = chatService ?: ChannelChatService(sessionManager)
        val messageParser = FeishuMessageParser()
        return startChannel(channel) { message ->
            val agentRequest = messageParser.parse(message).withSessionId(channel.sessionId)
            effectiveChatService.chat(message, channel, agentAdaptor, this, agentRequest)
        }
    }

    override fun connectionState(channelId: Long): ChannelConnectionState = webSocketMode.connectionState(channelId)

    override fun connectionStates(): List<ChannelConnectionState> = webSocketMode.connectionStates()

    /**
     * Stop channel (for WebSocket mode)
     */
    override fun stopChannel(channel: ChannelSpec) {
        getMode(channel).stop(channel)
    }

    override fun shutdown() {
        webSocketMode.shutdown()
    }

    /**
     * Handle send response
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
            contentMap["text"] as? String ?: ""
        } catch (e: Exception) {
            // If content is not a JSON object, return as-is (might be plain text)
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
 * Feishu event format
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
 * Feishu message format
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
 * Feishu sender information
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
