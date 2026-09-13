package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.channel.feishu.client.PlatformHttpClient
import com.agnetix.harnax.channel.feishu.client.PlatformResponse
import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.ChannelCallbackPipeline
import com.agnetix.harnax.channel.sdk.adaptor.ChannelCallbackResult
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
import com.agnetix.harnax.channel.sdk.service.ReplyMarkers
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import com.agnetix.harnax.channel.sdk.util.MessageDeduplicator
import com.lark.oapi.core.request.EventReq
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import org.slf4j.LoggerFactory
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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
    private val turnExecutor: ChannelTurnExecutor = ChannelTurnExecutor.SHARED,
    metricsSink: ChannelMetricsSink = NoOpChannelMetricsSink,
) : ChannelAdaptor {

    private val logger = LoggerFactory.getLogger(FeishuAdaptor::class.java)
    private val objectMapper = jacksonObjectMapper()

    // WebSocket communication mode instance
    private val webSocketMode: FeishuWebSocketMode = FeishuWebSocketMode(httpClient, turnExecutor, metricsSink)

    /** Feishu retries an un-acked event, so a callback must not run the same message twice. */
    private val callbackDedups = ConcurrentHashMap<Long, MessageDeduplicator>()

    private val messageParser = FeishuMessageParser()

    override fun getType(): ChannelType = ChannelType.FEISHU

    /**
     * Verify a callback signature.
     *
     * Feishu signs with the Encrypt Key, not the app secret, and with a plain digest rather than
     * an HMAC: `sha256Hex(timestamp + nonce + encryptKey + rawBody)`. Without an Encrypt Key the
     * platform sends no signature header, so there is nothing to check.
     *
     * [handleCallback] does its own verification through the official SDK; this covers callers that
     * only hold a request.
     */
    override fun verifySignature(request: ChannelRequest, channel: ChannelSpec): Boolean {
        val encryptKey = channel.encodingAesKey
        if (encryptKey.isNullOrBlank()) {
            logger.debug("Feishu signature check skipped: no Encrypt Key configured for channel ${channel.id}")
            return true
        }
        val signature = header(request, "X-Lark-Signature") ?: return false
        val timestamp = header(request, "X-Lark-Request-Timestamp") ?: return false
        val nonce = header(request, "X-Lark-Request-Nonce") ?: return false
        val expected = feishuSignature(timestamp, nonce, encryptKey, request.body)
        return MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), signature.lowercase().toByteArray(StandardCharsets.UTF_8))
    }

    /** Header lookup that does not depend on how the caller's server normalised the name. */
    private fun header(request: ChannelRequest, name: String): String? = request.headers[name] ?: request.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun feishuSignature(
        timestamp: String,
        nonce: String,
        encryptKey: String,
        body: String,
    ): String = MessageDigest.getInstance("SHA-256")
        .digest((timestamp + nonce + encryptKey + body).toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

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
     * Feishu cannot receive files from this service
     *
     * Delivering a file to a Feishu chat takes the media-upload API, which this adaptor never
     * wired up: [sendFile] stays on the SDK default and only posts a text notice. Generated files
     * therefore remain in the agent workspace for the user to pick up in the Web UI.
     */
    override fun supportsFileDelivery(): Boolean = false

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
     * Feishu is the one channel here with a real HTTP event-subscription contract.
     */
    override fun supportsCallback(): Boolean = true

    /**
     * Accept a Feishu event callback.
     *
     * Decryption, signature verification and the URL-verification challenge are delegated to the
     * official SDK's [EventDispatcher] rather than reimplemented: that scheme has no test vector
     * reachable from this service, so the SDK is the only implementation that can be trusted to
     * agree with the platform. The event body then goes through the same parser the WebSocket
     * transport uses, so the two cannot drift apart.
     *
     * The turn is handed to [turnExecutor] and the ack returns immediately: Feishu retries anything
     * it does not get an answer from within a few seconds, while an agent turn takes minutes.
     */
    override fun handleCallback(
        request: ChannelRequest,
        channel: ChannelSpec,
        pipeline: ChannelCallbackPipeline,
    ): ChannelCallbackResult {
        val messageHandler = agentBackedHandler(channel, pipeline.agentAdaptor, pipeline.chatService)
        val channelId = channel.id
        val encryptKey = channel.encodingAesKey
        if (encryptKey.isNullOrBlank()) {
            // This endpoint is exempt from service-to-service auth because the platform cannot
            // present a service token, so the Encrypt Key signature is the only thing standing
            // between an anonymous POST and an agent turn. Feishu skips signing when no Encrypt Key
            // is configured, which leaves nothing to verify — refuse rather than accept the event.
            logger.error("Feishu channel $channelId has no Encrypt Key; refusing an unverifiable callback")
            return ChannelCallbackResult.rejected(403, "encrypt key is required for callback mode")
        }
        var received: P2MessageReceiveV1? = null
        val dispatcher = EventDispatcher.newBuilder(channel.token.orEmpty(), encryptKey)
            .onP2MessageReceiveV1(
                object : ImService.P2MessageReceiveV1Handler() {
                    override fun handle(data: P2MessageReceiveV1?) {
                        received = data
                    }
                },
            )
            .build()
        val eventReq = EventReq().apply {
            body = request.body.toByteArray(StandardCharsets.UTF_8)
            headers = request.headers.entries.associate { (name, value) -> name.lowercase() to listOf(value) }
            httpPath = request.path
        }

        val response = try {
            dispatcher.handle(eventReq)
        } catch (e: Throwable) {
            logger.error("Feishu callback failed verification or decoding for channel $channelId: ${e.message}", e)
            return ChannelCallbackResult.rejected(400, "invalid callback")
        }

        val ackBody = response.body?.toString(StandardCharsets.UTF_8).orEmpty()
        val event = received
        if (response.statusCode != 200 || event == null) {
            // A URL-verification challenge, or an event type with no handler: answer as the SDK
            // says and start nothing.
            return ChannelCallbackResult(status = response.statusCode, body = ackBody)
        }

        // Parsing is deferred into the turn because it downloads any attached image, which must not
        // happen on the HTTP thread that owes Feishu an ack. These two getters are the only fields
        // needed to route and de-duplicate the event, and they read straight off the parsed model.
        val detail = event.event
        val msgKey = detail?.message?.messageId?.takeIf { it.isNotBlank() }
        val sessionId = detail?.message?.chatId ?: msgKey ?: ""
        val dedup = msgKey?.let { callbackDedups.computeIfAbsent(channelId) { MessageDeduplicator() } }
        if (msgKey != null && dedup != null && !dedup.tryBegin(msgKey)) {
            logger.debug("Duplicate Feishu callback ignored: messageId=$msgKey, channel=$channelId")
            return ChannelCallbackResult.ok(ackBody)
        }
        val commit = { if (msgKey != null) dedup?.commit(msgKey) }
        val rollback = { if (msgKey != null) dedup?.rollback(msgKey) }

        if (sessionId.isBlank()) {
            logger.warn("Feishu callback for channel $channelId carried no chat id and no message id")
            rollback()
            return ChannelCallbackResult.ok(ackBody)
        }

        turnExecutor.launchTurn(channelId, sessionId) {
            try {
                val message = webSocketMode.parseFeishuEvent(event, channel)
                if (message == null) {
                    // Same answer the WebSocket transport gives: name the type instead of ignoring
                    // the message, then treat the event as handled so a retry does not double-notice.
                    notifyUnreadable(channel, sessionId, event.event?.message?.messageType)
                    commit()
                    return@launchTurn
                }
                messageHandler(message)
                commit()
            } catch (e: Throwable) {
                logger.error("Feishu callback turn failed for channel $channelId: ${e.message}", e)
                rollback()
            }
        }
        return ChannelCallbackResult.ok(ackBody)
    }

    /**
     * Tell the user their message type cannot be handled.
     *
     * Goes through [sendMessage] rather than the WebSocket transport's own sender, so a webhook
     * channel replies over its robot URL and a long-connection channel over the Open API — the same
     * split every other reply obeys. Best-effort: a failed explanation must not also fail the ack.
     */
    private fun notifyUnreadable(
        channel: ChannelSpec,
        sessionId: String,
        platformType: String?,
    ) {
        val notice = ReplyMarkers.unsupportedMessageType(platformType)
        turnExecutor.launchTurn(channel.id, sessionId) {
            try {
                sendMessage(channel, sessionId, notice)
            } catch (e: Exception) {
                logger.warn("Unable to tell session=$sessionId that $platformType is unsupported: ${e.message}", e)
            }
        }
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
    ): Boolean = startChannel(channel, agentBackedHandler(channel, agentAdaptor, chatService ?: ChannelChatService(sessionManager)))

    /**
     * The handler that runs one inbound message through the full agent pipeline.
     *
     * Shared by the long-connection listener and the HTTP callback on purpose: which text counts as
     * a command is Feishu-specific knowledge, and two entry points that each keep their own copy
     * are how a webhook channel ends up not answering `/clear`.
     */
    private fun agentBackedHandler(
        channel: ChannelSpec,
        agentAdaptor: AgentAdaptor,
        chatService: ChannelChatService,
    ): suspend (ChannelMessage) -> Unit = { message ->
        val agentRequest = messageParser.parse(message).withSessionId(channel.sessionId)
        chatService.chat(message, channel, agentAdaptor, this, agentRequest)
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
        callbackDedups.clear()
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
