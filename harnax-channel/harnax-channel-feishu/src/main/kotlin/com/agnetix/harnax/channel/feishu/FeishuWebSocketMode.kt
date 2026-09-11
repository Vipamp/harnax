package com.agnetix.harnax.channel.feishu

import com.agnetix.harnax.channel.feishu.client.PlatformHttpClient
import com.agnetix.harnax.channel.feishu.client.PlatformResponse
import com.agnetix.harnax.channel.sdk.adaptor.ChannelCommunicationMode
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.dispatch.ChannelTurnExecutor
import com.agnetix.harnax.channel.sdk.error.ChannelSendException
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageType
import com.agnetix.harnax.channel.sdk.message.RichMessage
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionState
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionTracker
import com.agnetix.harnax.channel.sdk.monitor.ChannelMetricsSink
import com.agnetix.harnax.channel.sdk.monitor.NoOpChannelMetricsSink
import com.agnetix.harnax.channel.sdk.util.MessageDeduplicator
import com.agnetix.harnax.channel.sdk.util.TextChunker
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import org.slf4j.LoggerFactory
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.lang.reflect.Method
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * Lifecycle notes:
 * - The listener is registered in [listeners] *before* the client is started, and marked
 *   closing on stop. The previous code registered the client only after `start()` returned,
 *   so a stop() during connect could not find it and leaked a live WebSocket forever.
 * - `com.lark.oapi.ws.Client.start()` is expected to block for the lifetime of the
 *   connection (that is why it runs on a dedicated thread). Because that behaviour is not
 *   contractual, a fast return with no event ever received is treated as "liveness
 *   unconfirmed" instead of "connection died": only a listener that demonstrably worked,
 *   or one that held a connection for a while, is handed back to the reconcile loop for a
 *   restart. That asymmetry is deliberate — the wrong guess would restart healthy channels.
 * - Inbound events are dispatched to [ChannelTurnExecutor]; the SDK callback thread must
 *   never block on the agent, and image download (which is part of parsing) belongs to the
 *   turn, not to the reader thread.
 */
class FeishuWebSocketMode(
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
    private val turnExecutor: ChannelTurnExecutor = ChannelTurnExecutor.SHARED,
    private val metricsSink: ChannelMetricsSink = NoOpChannelMetricsSink,
) : ChannelCommunicationMode {

    private val logger = LoggerFactory.getLogger(FeishuWebSocketMode::class.java)
    private val objectMapper = jacksonObjectMapper()

    private val tracker = ChannelConnectionTracker(metricsSink, ChannelType.FEISHU.code)

    /** One entry per channel whose listener we asked for. */
    private val listeners = ConcurrentHashMap<Long, FeishuListener>()

    /** msgId bookkeeping per channel; a message is only remembered once the turn succeeded. */
    private val deduplicators = ConcurrentHashMap<Long, MessageDeduplicator>()

    /** appId -> token, so a chatty channel does not exchange a new tenant_access_token per message. */
    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    /** Per-channel listener bookkeeping. */
    private inner class FeishuListener(val channel: ChannelSpec) {
        val closing = AtomicBoolean(false)

        @Volatile
        var client: WsClient? = null

        @Volatile
        var thread: Thread? = null

        @Volatile
        var messageHandler: (suspend (ChannelMessage) -> Unit)? = null

        @Volatile
        var startedAt: Long = 0

        @Volatile
        var eventsReceived: Long = 0
    }

    private class CachedToken(
        val token: String,
        val expiresAt: Long,
    )

    override fun getModeName(): String = "websocket"

    override fun isCallbackMode(): Boolean = false

    override fun connectionState(channelId: Long): ChannelConnectionState = tracker.state(channelId)

    override fun connectionStates(): List<ChannelConnectionState> = tracker.snapshot()

    /**
     * Start WebSocket long connection
     *
     * @param channel Channel configuration
     * @param messageHandler Message processing function
     */
    override fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        val existing = listeners[channel.id]
        if (existing != null && !existing.closing.get()) {
            logger.warn("WebSocket connection already exists for channel: ${channel.id}")
            return
        }

        val appId = channel.appId
        val appSecret = channel.appSecret
        if (appId.isNullOrBlank() || appSecret.isNullOrBlank()) {
            tracker.markFailed(channel.id, "missing appId or appSecret")
            throw IllegalArgumentException("WebSocket mode requires appId and appSecret for channel: ${channel.id}")
        }

        val holder = FeishuListener(channel)
        holder.messageHandler = messageHandler
        if (listeners.putIfAbsent(channel.id, holder) != null) {
            logger.warn("WebSocket connection for channel ${channel.id} was started concurrently, ignoring this request")
            return
        }
        tracker.markConnecting(channel.id)
        logger.info("Starting WebSocket connection for channel: ${channel.id}")

        val wsClient = try {
            val eventDispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(messageEventHandler(holder))
                .build()
            WsClient.Builder(appId, appSecret)
                .eventHandler(eventDispatcher)
                .autoReconnect(true)
                .build()
        } catch (e: Exception) {
            listeners.remove(channel.id, holder)
            tracker.markFailed(channel.id, e.message ?: "client build failed")
            logger.error("Failed to build Feishu WebSocket client for channel: ${channel.id}", e)
            throw ChannelSendException(
                channelType = ChannelType.FEISHU,
                platformErrorCode = null,
                message = "Failed to start WebSocket: ${e.message}",
                cause = e,
            )
        }
        holder.client = wsClient
        holder.startedAt = System.currentTimeMillis()

        val thread = Thread({ runConnection(holder, wsClient) }, "feishu-ws-channel-${channel.id}")
        holder.thread = thread
        thread.isDaemon = true
        thread.start()
    }

    /**
     * Stop WebSocket long connection
     *
     * @param channel Channel configuration
     */
    override fun stop(channel: ChannelSpec) {
        val holder = listeners.remove(channel.id)
        deduplicators.remove(channel.id)
        if (holder == null) {
            tracker.markStopped(channel.id)
            logger.warn("No WebSocket connection found for channel: ${channel.id}")
            return
        }
        // Closing first makes the reader thread and any late event give up on their own.
        holder.closing.set(true)
        disconnect(holder.client)
        val thread = holder.thread
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_MS) }
            if (thread.isAlive) {
                logger.warn("Feishu WebSocket thread for channel ${channel.id} did not exit within ${STOP_JOIN_MS}ms")
            }
        }
        tracker.markStopped(channel.id)
        logger.info("WebSocket connection stopped for channel: ${channel.id}")
    }

    /** Stop every listener; called once on application shutdown. */
    fun shutdown() {
        listeners.values.forEach { holder ->
            runCatching { stop(holder.channel) }
        }
        listeners.clear()
        deduplicators.clear()
        tokenCache.clear()
    }

    /** WsClient.disconnect() is not public in oapi-sdk 2.4.0, so it is invoked reflectively. */
    private fun disconnect(client: WsClient?) {
        if (client == null) return
        val method = DISCONNECT_METHOD.value ?: run {
            logger.warn("Feishu SDK exposes no disconnect(); leaving the socket to the daemon thread")
            return
        }
        try {
            method.invoke(client)
        } catch (e: Exception) {
            logger.error("Failed to disconnect WebSocket: ${e.message}", e)
        }
    }

    /**
     * Runs the blocking `start()` call on the listener's own thread and translates its
     * outcome into a connection state the reconcile loop can act on.
     */
    private fun runConnection(
        holder: FeishuListener,
        client: WsClient,
    ) {
        val channelId = holder.channel.id
        if (holder.closing.get()) {
            // stop() ran while this thread was being scheduled; do not open a socket nobody owns.
            disconnect(client)
            listeners.remove(channelId, holder)
            return
        }
        try {
            client.start()
            if (holder.closing.get()) return
            val aliveMs = System.currentTimeMillis() - holder.startedAt
            if (holder.eventsReceived > 0 || aliveMs > FAST_RETURN_MS) {
                // The socket demonstrably worked and has now ended: ask for a restart.
                tracker.markFailed(channelId, "connection ended after ${aliveMs}ms")
            } else {
                logger.warn(
                    "Feishu start() returned after ${aliveMs}ms without receiving any event for channel $channelId; " +
                        "treating liveness as unconfirmed instead of restarting",
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!holder.closing.get()) tracker.markFailed(channelId, "interrupted")
        } catch (e: Throwable) {
            if (holder.closing.get()) return
            logger.error("WebSocket connection failed for channel: $channelId", e)
            tracker.markFailed(channelId, e.message ?: "ws client failure")
        } finally {
            // Remove only our own registration, so a restart that already happened survives.
            listeners.remove(channelId, holder)
        }
    }

    private fun messageEventHandler(holder: FeishuListener) = object : ImService.P2MessageReceiveV1Handler() {
        override fun handle(data: P2MessageReceiveV1?) {
            val channelId = holder.channel.id
            if (data == null) {
                logger.warn("Received null message event for channel: $channelId")
                return
            }
            if (holder.closing.get() || listeners[channelId] !== holder) {
                logger.debug("Ignoring Feishu event from a superseded listener, channel: $channelId")
                return
            }
            holder.eventsReceived++
            // First event is the only positive proof the connection is really serving traffic.
            tracker.markConnected(channelId)

            // Feishu SDK uses at-least-once delivery, same event may be delivered multiple times
            val msgKey = data.event?.message?.messageId?.takeIf { it.isNotBlank() }
            val dedup = msgKey?.let { deduplicators.computeIfAbsent(channelId) { MessageDeduplicator() } }
            if (msgKey != null && dedup != null && !dedup.tryBegin(msgKey)) {
                logger.debug("Duplicate message event ignored: messageId=$msgKey, channel=$channelId")
                tracker.onDuplicateMessage(channelId)
                return
            }
            val rollback = {
                if (msgKey != null) dedup?.rollback(msgKey)
            }
            val commit = {
                if (msgKey != null) dedup?.commit(msgKey)
            }

            val handler = holder.messageHandler
            if (handler == null) {
                logger.warn("No message handler registered for channel: $channelId")
                rollback()
                return
            }

            val sessionId = data.event?.message?.chatId ?: msgKey ?: ""
            tracker.markMessageReceived(channelId)
            turnExecutor.launchTurn(channelId, sessionId) {
                try {
                    val channelMessage = parseFeishuEvent(data, holder.channel)
                    if (channelMessage == null) {
                        // Nothing was forwarded, so a platform redelivery must still be accepted.
                        rollback()
                        return@launchTurn
                    }
                    handler(channelMessage)
                    commit()
                } catch (e: Exception) {
                    logger.error("Failed to handle Feishu message event for channel: $channelId", e)
                    rollback()
                }
            }
        }
    }

    /**
     * Send text message
     * Sent via Feishu Open API (requires tenant_access_token)
     *
     * Automatically splits long messages into chunks to stay within Feishu's
     * message size limit (~150KB content JSON). Each chunk is sent as a separate
     * message to preserve the full response.
     */
    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        val appId = requireCredentials(channel, "send messages")
        val appSecret = channel.appSecret!!

        val token = getTenantAccessToken(appId, appSecret)

        // Split long messages into chunks to respect Feishu's content size limit
        val chunks = TextChunker.splitByUtf8Bytes(message, MAX_MESSAGE_CHUNK_BYTES)
        if (chunks.size > 1) {
            logger.info("Splitting long message into {} chunks for session={}, totalLength={}", chunks.size, sessionId, message.length)
        }

        val started = System.currentTimeMillis()
        var error: Throwable? = null
        try {
            for ((index, chunk) in chunks.withIndex()) {
                sendSingleMessage(channel, sessionId, chunk, token, index + 1, chunks.size)
            }
        } catch (e: Exception) {
            error = e
            throw e
        } finally {
            metricsSink.onSendCompleted(channel.id, System.currentTimeMillis() - started, error)
        }
    }

    /**
     * Send a single message chunk to Feishu.
     */
    private suspend fun sendSingleMessage(
        channel: ChannelSpec,
        sessionId: String,
        message: String,
        token: String,
        chunkIndex: Int,
        totalChunks: Int,
    ) {
        val contentJson = objectMapper.writeValueAsString(mapOf("text" to message))
        val messageBody = mapOf(
            "receive_id" to sessionId,
            "msg_type" to "text",
            "content" to contentJson,
        )

        if (totalChunks > 1) {
            logger.debug("Sending Feishu message chunk {}/{}: receive_id={}, length={}", chunkIndex, totalChunks, sessionId, message.length)
        } else {
            logger.debug("Sending Feishu message: receive_id={}, msg_type={}, contentLength={}", sessionId, "text", message.length)
        }

        val url = "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id"
        val response = httpClient.postJson(
            url,
            messageBody,
            mapOf(
                "Authorization" to "Bearer $token",
            ),
        )

        if (response is PlatformResponse.Error) {
            logger.error("Feishu API error response: status={}, body={}, chunk={}/{}", response.statusCode, response.body, chunkIndex, totalChunks)
        }

        handleSendResponse(response)
    }

    /**
     * Send rich message
     */
    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        val appId = requireCredentials(channel, "send rich messages")
        val appSecret = channel.appSecret!!

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
        val started = System.currentTimeMillis()
        val response = httpClient.postJson(
            url,
            messageBody,
            mapOf(
                "Authorization" to "Bearer $token",
            ),
        )
        metricsSink.onSendCompleted(channel.id, System.currentTimeMillis() - started, null)

        handleSendResponse(response)
    }

    private fun requireCredentials(
        channel: ChannelSpec,
        purpose: String,
    ): String {
        val appId = channel.appId
        val appSecret = channel.appSecret
        if (appId.isNullOrBlank() || appSecret.isNullOrBlank()) {
            throw ChannelSendException(
                channelType = ChannelType.FEISHU,
                platformErrorCode = null,
                message = "WebSocket mode requires appId and appSecret to $purpose",
            )
        }
        return appId
    }

    /**
     * Get tenant_access_token
     * Used to call Feishu Open API
     *
     * Tokens are cached per appId until shortly before they expire: they are valid for ~2h,
     * and fetching one per sent message added an extra round trip and hit the platform's
     * token-exchange rate limit under load.
     */
    private suspend fun getTenantAccessToken(
        appId: String,
        appSecret: String,
    ): String {
        val now = System.currentTimeMillis()
        tokenCache[appId]?.let { if (it.expiresAt > now) return it.token }

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
                        val token = json.path("tenant_access_token").asText()
                        val expireSeconds = json.path("expire").asLong(7200L)
                        val ttlMs = ((expireSeconds - TOKEN_REFRESH_SAFETY_SEC).coerceAtLeast(1L)) * 1000
                        tokenCache[appId] = CachedToken(token, now + ttlMs)
                        token
                    } else {
                        val msg = json.path("msg").asText("unknown")
                        tokenCache.remove(appId)
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
                tokenCache.remove(appId)
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
     * Parse Feishu event to ChannelMessage
     */
    private suspend fun parseFeishuEvent(event: P2MessageReceiveV1, channel: ChannelSpec): ChannelMessage? {
        val channelId = channel.id
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

            var textContent: String = ""
            var imageUrls: List<String> = emptyList()

            when (messageType) {
                "text" -> {
                    val contentStr = message.content ?: "{}"
                    try {
                        val contentJson = objectMapper.readTree(contentStr)
                        // Check for post (rich text) format: {"content": [[[...]]]}
                        if (contentJson.has("content") && contentJson.get("content").isArray) {
                            val postResult = parsePostContent(contentJson, channel)
                            textContent = postResult.first
                            imageUrls = postResult.second
                        } else {
                            textContent = contentJson.path("text").asText("")
                            imageUrls = emptyList()
                        }
                    } catch (e: Exception) {
                        logger.warn("Failed to parse message content: ${e.message}")
                        textContent = contentStr
                        imageUrls = emptyList()
                    }
                }
                "image" -> {
                    val imageKey = parseImageKey(message.content)
                    if (imageKey != null) {
                        val dataUrl = downloadFeishuImage(channel, imageKey)
                        textContent = "[image]"
                        imageUrls = if (dataUrl != null) listOf(dataUrl) else emptyList()
                    } else {
                        logger.warn("Failed to parse image_key from content: ${message.content}")
                        textContent = message.content ?: ""
                        imageUrls = emptyList()
                    }
                }
                else -> {
                    logger.info("Unsupported message type: $messageType, using raw content")
                    textContent = message.content ?: ""
                    imageUrls = emptyList()
                }
            }

            ChannelMessage(
                messageId = messageId,
                sessionId = chatId,
                senderId = senderId,
                content = textContent,
                channelType = ChannelType.FEISHU,
                messageType = when (messageType) {
                    "text" -> MessageType.TEXT
                    "image" -> MessageType.IMAGE
                    "file" -> MessageType.FILE
                    else -> MessageType.TEXT
                },
                imageUrls = imageUrls,
                rawContent = event,
            )
        } catch (e: Exception) {
            logger.error("Failed to parse Feishu event for channel: $channelId", e)
            null
        }
    }

    /**
     * Parse image_key from Feishu image message content JSON.
     *
     * Feishu image message content format: {"image_key": "img_v3_xxx"}
     * Returns null if content is malformed or image_key is missing.
     */
    private fun parseImageKey(content: String?): String? {
        if (content.isNullOrBlank()) return null
        return try {
            val json = objectMapper.readTree(content)
            val key = json.path("image_key").asText("")
            key.ifBlank { null }
        } catch (e: Exception) {
            logger.warn("Failed to parse image content JSON: ${e.message}")
            null
        }
    }

    /**
     * Parse Feishu post (rich text) content format.
     *
     * Post content structure:
     * {
     *   "title": "optional title",
     *   "content": [
     *     [
     *       {"tag": "text", "text": "some text", "style": []},
     *       {"tag": "img", "image_key": "img_v3_xxx", "width": 800, "height": 600}
     *     ]
     *   ]
     * }
     *
     * @return Pair of (extractedText, downloadedImageUrls)
     */
    private suspend fun parsePostContent(
        contentJson: tools.jackson.databind.JsonNode,
        channel: ChannelSpec,
    ): Pair<String, List<String>> {
        val textParts = mutableListOf<String>()
        val imageUrls = mutableListOf<String>()

        // Extract title if present
        val title = contentJson.path("title").asText("")
        if (title.isNotBlank()) textParts.add(title)

        // Parse content array: [[{tag, ...}, ...], ...]
        val contentArray = contentJson.get("content")
        if (contentArray != null && contentArray.isArray) {
            for (paragraph in contentArray) {
                if (!paragraph.isArray) continue
                for (element in paragraph) {
                    val tag = element.path("tag").asText("")
                    when (tag) {
                        "text" -> {
                            val text = element.path("text").asText("")
                            if (text.isNotBlank()) textParts.add(text)
                        }
                        "img" -> {
                            val imageKey = element.path("image_key").asText("")
                            if (imageKey.isNotBlank()) {
                                val dataUrl = downloadFeishuImage(channel, imageKey)
                                if (dataUrl != null) {
                                    imageUrls.add(dataUrl)
                                } else {
                                    logger.warn("Failed to download image in post: $imageKey")
                                }
                            }
                        }
                        "a" -> {
                            val href = element.path("href").asText("")
                            val text = element.path("text").asText(href)
                            if (text.isNotBlank()) textParts.add(text)
                        }
                        "at" -> {
                            val userName = element.path("user_name").asText("@user")
                            textParts.add(userName)
                        }
                    }
                }
            }
        }

        return Pair(textParts.joinToString(" ").trim(), imageUrls.toList())
    }

    /**
     * Download image from Feishu Image API and return as base64 data URL.
     *
     * Feishu Image API: GET /open-apis/im/v1/images/{image_key}
     * Returns the raw image binary, so we need to add the Authorization header.
     * The result is encoded as a data URL (data:image/jpeg;base64,...) for downstream consumption.
     *
     * Returns null if download fails.
     */
    private suspend fun downloadFeishuImage(channel: ChannelSpec, imageKey: String): String? {
        val appId = channel.appId
        val appSecret = channel.appSecret
        if (appId.isNullOrBlank() || appSecret.isNullOrBlank()) {
            logger.warn("Cannot download image: appId/appSecret not configured for channel ${channel.id}")
            return null
        }

        val url = "https://open.feishu.cn/open-apis/im/v1/images/$imageKey"
        return try {
            val token = getTenantAccessToken(appId, appSecret)
            val headers = mapOf("Authorization" to "Bearer $token")
            val bytes = httpClient.getBinary(url, headers)
            if (bytes != null && bytes.isNotEmpty()) {
                val base64 = Base64.getEncoder().encodeToString(bytes)
                "data:image/jpeg;base64,$base64"
            } else {
                logger.warn("Empty response when downloading image: $imageKey")
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to download image $imageKey for channel ${channel.id}: ${e.message}", e)
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

    companion object {
        // Feishu text message content limit (bytes). The actual API limit is ~150KB for content JSON;
        // use a conservative 20KB per chunk to leave headroom for JSON wrapper and metadata.
        private const val MAX_MESSAGE_CHUNK_BYTES = 20_000

        // Refresh ahead of the platform-side expiry so a token never goes stale mid-request.
        private const val TOKEN_REFRESH_SAFETY_SEC = 300L

        private const val STOP_JOIN_MS = 3_000L

        // start() coming back this fast tells us nothing about whether it ever connected.
        private const val FAST_RETURN_MS = 10_000L

        /** Resolved once instead of on every stop; the SDK keeps disconnect() non-public. */
        private val DISCONNECT_METHOD: Lazy<Method?> = lazy {
            runCatching {
                WsClient::class.java.getDeclaredMethod("disconnect").apply { isAccessible = true }
            }.getOrNull()
        }
    }
}
