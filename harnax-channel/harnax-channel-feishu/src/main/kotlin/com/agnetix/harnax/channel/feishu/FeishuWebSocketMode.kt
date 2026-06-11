package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.channel.feishu.client.PlatformHttpClient
import com.agnetix.harnax.channel.feishu.client.PlatformResponse
import com.agnetix.harnax.channel.sdk.adaptor.ChannelCommunicationMode
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.error.ChannelSendException
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageType
import com.agnetix.harnax.channel.sdk.message.RichMessage
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import com.lark.oapi.ws.Client as WsClient

/**
 * Feishu WebSocket Long Connection Mode Implementation
 * Implements WebSocket full-duplex communication based on Feishu official Java SDK (oapi-sdk 2.4.0)
 *
 * Features:
 * - No public IP or domain required, only needs public network access
 * - Built-in encryption and authentication, no additional signature handling needed
 * - Suitable for intranet development environment and enterprise private deployment
 * - Supports automatic reconnection
 * - Message deduplication based on messageId to handle at-least-once delivery
 *
 * Notes:
 * - WebSocket is only used for receiving messages, sending messages still requires calling Open API
 * - Each Channel corresponds to an independent WebSocket connection
 * - Feishu WebSocket operates in cluster mode, only one client will receive messages for the same application
 * - Feishu SDK uses at-least-once delivery, events may be re-delivered on reconnection or retry
 */
class FeishuWebSocketMode(
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
) : ChannelCommunicationMode {

    private val logger = LoggerFactory.getLogger(FeishuWebSocketMode::class.java)
    private val objectMapper = jacksonObjectMapper()

    // Stores WebSocket client for each Channel
    private val wsClients = ConcurrentHashMap<Long, WsClient>()

    // Stores message handler for each Channel
    private val messageHandlers = ConcurrentHashMap<Long, suspend (ChannelMessage) -> Unit>()

    // Tracks processed message IDs per channel for deduplication (Feishu uses at-least-once delivery)
    private val processedMessageIds = ConcurrentHashMap<Long, MutableSet<String>>()

    companion object {
        // Max number of message IDs to keep per channel before cleanup
        private const val MAX_PROCESSED_IDS = 1000
    }

    override fun getModeName(): String = "websocket"

    override fun isCallbackMode(): Boolean = false

    /**
     * Start WebSocket long connection
     *
     * @param channel Channel configuration
     * @param messageHandler Message processing function
     */
    override fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        if (wsClients.containsKey(channel.id)) {
            logger.warn("WebSocket connection already exists for channel: ${channel.id}")
            return
        }

        val appId = channel.appId
        val appSecret = channel.appSecret

        if (appId.isNullOrBlank() || appSecret.isNullOrBlank()) {
            throw IllegalArgumentException("WebSocket mode requires appId and appSecret for channel: ${channel.id}")
        }

        logger.info("Starting WebSocket connection for channel: ${channel.id}")

        try {
            // Create message event handler
            val messageEventHandler = object : ImService.P2MessageReceiveV1Handler() {
                override fun handle(data: P2MessageReceiveV1?) {
                    if (data == null) {
                        logger.warn("Received null message event for channel: ${channel.id}")
                        return
                    }

                    // Extract messageId early for deduplication
                    // Feishu SDK uses at-least-once delivery, same event may be delivered multiple times
                    val messageId = data.event?.message?.messageId
                    if (!messageId.isNullOrBlank() && !markMessageProcessed(channel.id, messageId)) {
                        logger.debug("Duplicate message event ignored: messageId=$messageId, channel=${channel.id}")
                        return
                    }

                    // Process message asynchronously (do not block event processing)
                    runBlocking {
                        try {
                            val channelMessage = parseFeishuEvent(data, channel.id)
                            if (channelMessage != null) {
                                val handler = messageHandlers[channel.id]
                                if (handler != null) {
                                    handler(channelMessage)
                                } else {
                                    logger.warn("No message handler registered for channel: ${channel.id}")
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Failed to handle Feishu message event for channel: ${channel.id}", e)
                        }
                    }
                }
            }

            // Create event dispatcher and register message handler
            val eventDispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(messageEventHandler)
                .build()

            // Create WebSocket client
            val wsClient = WsClient.Builder(appId, appSecret)
                .eventHandler(eventDispatcher)
                .autoReconnect(true)
                .build()

            // Start in background thread (non-blocking)
            Thread {
                try {
                    wsClient.start()
                } catch (e: Exception) {
                    logger.error("WebSocket connection failed for channel: ${channel.id}", e)
                    wsClients.remove(channel.id)
                }
            }.apply {
                isDaemon = true
                name = "feishu-ws-channel-${channel.id}"
                start()
            }

            wsClients[channel.id] = wsClient
            messageHandlers[channel.id] = messageHandler
            logger.info("WebSocket connection started for channel: ${channel.id}")
        } catch (e: Exception) {
            logger.error("Failed to start WebSocket connection for channel: ${channel.id}", e)
            throw ChannelSendException(
                channelType = ChannelType.FEISHU,
                platformErrorCode = null,
                message = "Failed to start WebSocket: ${e.message}",
                cause = e,
            )
        }
    }

    /**
     * Stop WebSocket long connection
     *
     * @param channel Channel configuration
     */
    override fun stop(channel: ChannelSpec) {
        val wsClient = wsClients.remove(channel.id)
        if (wsClient != null) {
            try {
                logger.info("WebSocket connection removed for channel: ${channel.id}")
            } catch (e: Exception) {
                logger.error("Failed to stop WebSocket connection for channel: ${channel.id}", e)
            }
        } else {
            logger.warn("No WebSocket connection found for channel: ${channel.id}")
        }
    }

    /**
     * Send text message
     * Sent via Feishu Open API (requires tenant_access_token)
     */
    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        val appId = channel.appId
        val appSecret = channel.appSecret

        if (appId.isNullOrBlank() || appSecret.isNullOrBlank()) {
            throw ChannelSendException(
                channelType = ChannelType.FEISHU,
                platformErrorCode = null,
                message = "WebSocket mode requires appId and appSecret to send messages",
            )
        }

        // Get tenant_access_token
        val token = getTenantAccessToken(appId, appSecret)

        // Build message body (must include receive_id)
        // Note: Feishu API requires content field to be JSON string, not object
        val contentJson = objectMapper.writeValueAsString(mapOf("text" to message))
        val messageBody = mapOf(
            "receive_id" to sessionId,
            "msg_type" to "text",
            "content" to contentJson,
        )

        logger.debug("Sending Feishu message: receive_id={}, msg_type={}, content={}", sessionId, "text", message)

        // Call Feishu Open API to send message
        val url = "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id"
        val response = httpClient.postJson(
            url,
            messageBody,
            mapOf(
                "Authorization" to "Bearer $token",
            ),
        )

        if (response is PlatformResponse.Error) {
            logger.error("Feishu API error response: status={}, body={}", response.statusCode, response.body)
        }

        handleSendResponse(response)
    }

    /**
     * Send rich message
     */
    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        val appId = channel.appId
        val appSecret = channel.appSecret

        if (appId.isNullOrBlank() || appSecret.isNullOrBlank()) {
            throw ChannelSendException(
                channelType = ChannelType.FEISHU,
                platformErrorCode = null,
                message = "WebSocket mode requires appId and appSecret to send rich messages",
            )
        }

        // Get tenant_access_token
        val token = getTenantAccessToken(appId, appSecret)

        // Build rich message body (must include receive_id)
        val content = FeishuMessageBuilder.buildFromRichMessage(richMessage)
        val contentJson = objectMapper.writeValueAsString(content["content"])
        val messageBody = mapOf(
            "receive_id" to sessionId,
            "msg_type" to content["msg_type"],
            "content" to contentJson,
        )

        // Call Feishu Open API to send message
        val url = "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id"
        val response = httpClient.postJson(
            url,
            messageBody,
            mapOf(
                "Authorization" to "Bearer $token",
            ),
        )

        handleSendResponse(response)
    }

    /**
     * Get tenant_access_token
     * Used to call Feishu Open API
     */
    private suspend fun getTenantAccessToken(appId: String, appSecret: String): String {
        val requestBody = mapOf(
            "app_id" to appId,
            "app_secret" to appSecret,
        )

        val response = httpClient.postJson(
            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
            requestBody,
        )

        return when (response) {
            is PlatformResponse.Success -> {
                try {
                    val json = objectMapper.readTree(response.body)
                    val code = json.path("code").asInt(-1)
                    if (code == 0) {
                        json.path("tenant_access_token").asText()
                    } else {
                        val msg = json.path("msg").asText("unknown")
                        throw ChannelSendException(
                            channelType = ChannelType.FEISHU,
                            platformErrorCode = code.toString(),
                            message = "Failed to get tenant_access_token: $msg",
                        )
                    }
                } catch (e: ChannelSendException) {
                    throw e
                } catch (e: Exception) {
                    throw ChannelSendException(
                        channelType = ChannelType.FEISHU,
                        platformErrorCode = null,
                        message = "Failed to parse tenant_access_token response: ${e.message}",
                        cause = e,
                    )
                }
            }
            is PlatformResponse.Error -> {
                throw ChannelSendException(
                    channelType = ChannelType.FEISHU,
                    platformErrorCode = response.platformCode,
                    message = "Failed to get tenant_access_token: HTTP ${response.statusCode}",
                    cause = response.exception,
                )
            }
        }
    }

    /**
     * Mark a message as processed and return whether it's a new message.
     *
     * Uses a per-channel Set to track processed message IDs.
     * Returns true if the message is new (not previously seen), false if duplicate.
     * When the set exceeds [MAX_PROCESSED_IDS], it is cleared to prevent unbounded growth.
     *
     * @param channelId Channel ID
     * @param messageId Feishu message ID
     * @return true if new message, false if already processed
     */
    private fun markMessageProcessed(channelId: Long, messageId: String): Boolean {
        val ids = processedMessageIds.computeIfAbsent(channelId) {
            Collections.synchronizedSet(LinkedHashSet())
        }
        synchronized(ids) {
            if (ids.contains(messageId)) {
                return false
            }
            ids.add(messageId)
            // Prevent unbounded growth: clear oldest entries when exceeding limit
            if (ids.size > MAX_PROCESSED_IDS) {
                val toRemove = ids.take(ids.size - MAX_PROCESSED_IDS / 2)
                ids.removeAll(toRemove.toSet())
                logger.debug("Cleaned up processed message IDs for channel $channelId, remaining=${ids.size}")
            }
            return true
        }
    }

    /**
     * Parse Feishu event to ChannelMessage
     */
    private fun parseFeishuEvent(event: P2MessageReceiveV1, channelId: Long): ChannelMessage? {
        return try {
            val message = event.event?.message

            if (message == null) {
                logger.warn("Message is null in event for channel: $channelId")
                return null
            }

            val messageId = message.messageId ?: ""
            val chatId = message.chatId ?: ""
            val messageType = message.messageType ?: "text"
            val senderId = event.event?.sender?.senderId?.openId ?: ""

            val content = when (messageType) {
                "text" -> {
                    val contentStr = message.content ?: "{}"
                    try {
                        val contentJson = objectMapper.readTree(contentStr)
                        contentJson.path("text").asText("")
                    } catch (e: Exception) {
                        logger.warn("Failed to parse message content: ${e.message}")
                        contentStr
                    }
                }
                else -> {
                    logger.info("Unsupported message type: $messageType, using raw content")
                    message.content ?: ""
                }
            }

            ChannelMessage(
                messageId = messageId,
                sessionId = chatId,
                senderId = senderId,
                content = content,
                channelType = ChannelType.FEISHU,
                messageType = when (messageType) {
                    "text" -> MessageType.TEXT
                    "image" -> MessageType.IMAGE
                    "file" -> MessageType.FILE
                    else -> MessageType.TEXT
                },
                rawContent = event,
            )
        } catch (e: Exception) {
            logger.error("Failed to parse Feishu event for channel: $channelId", e)
            null
        }
    }

    /**
     * Handle send response
     */
    private fun handleSendResponse(response: PlatformResponse) {
        when (response) {
            is PlatformResponse.Success -> {
                try {
                    val json = objectMapper.readTree(response.body)
                    val code = json.path("code").asInt(-1)
                    val msg = json.path("msg").asText("unknown")

                    logger.debug("Feishu API response: code={}, msg={}", code, msg)

                    if (code == 0) {
                        logger.info("Feishu message sent successfully via WebSocket mode")
                    } else {
                        val fullError = json.toString()
                        logger.error("Feishu API returned error code {}: {}", code, fullError)
                        throw ChannelSendException(
                            channelType = ChannelType.FEISHU,
                            platformErrorCode = code.toString(),
                            message = "Feishu send failed (code=$code): $msg",
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
                logger.error("Feishu HTTP error: status={}, body={}", response.statusCode, response.body)
                throw ChannelSendException(
                    channelType = ChannelType.FEISHU,
                    platformErrorCode = response.platformCode,
                    message = "Feishu HTTP error: ${response.statusCode} - ${response.platformMessage ?: response.body}",
                    cause = response.exception,
                )
            }
        }
    }
}
