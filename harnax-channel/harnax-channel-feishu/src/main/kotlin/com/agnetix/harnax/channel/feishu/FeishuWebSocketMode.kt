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
import com.agnetix.harnax.channel.sdk.service.ReplyMarkers
import com.agnetix.harnax.channel.sdk.util.MessageDeduplicator
import com.agnetix.harnax.channel.sdk.util.TextChunker
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import org.slf4j.LoggerFactory
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
 * - The listener holder is registered in [listeners] *before* the client is started, and is
 *   retired only by [stop]. Registering early means a stop() during connect always finds the
 *   client; not retiring it later means the socket and the holder die together rather than the
 *   holder being dropped out from under a live connection.
 * - [FeishuWsTransport.start] is **not** blocking — the SDK schedules the handshake on its own
 *   executor and returns — so a clean return says nothing about liveness. The first inbound
 *   event is the only positive proof, and that is where [tracker.markConnected] runs.
 * - Inbound events are dispatched to [ChannelTurnExecutor]; the SDK callback thread must
 *   never block on the agent, and image download (which is part of parsing) belongs to the
 *   turn, not to the reader thread.
 */
class FeishuWebSocketMode(
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
    private val turnExecutor: ChannelTurnExecutor = ChannelTurnExecutor.SHARED,
    private val metricsSink: ChannelMetricsSink = NoOpChannelMetricsSink,
    private val transportFactory: FeishuWsTransportFactory = SdkFeishuWsTransportFactory,
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
        var transport: FeishuWsTransport? = null

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

        if (channel.appId.isNullOrBlank() || channel.appSecret.isNullOrBlank()) {
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

        val transport = try {
            transportFactory.create(channel, messageEventHandler(holder))
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
        holder.transport = transport
        holder.startedAt = System.currentTimeMillis()

        val thread = Thread({ runConnection(holder, transport) }, "feishu-ws-channel-${channel.id}")
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
        holder.transport?.close()
        val thread = holder.thread
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_MS) }
            if (thread.isAlive) {
                logger.warn("Feishu WebSocket thread for channel ${channel.id} did not exit within ${STOP_JOIN_MS}ms")
            }
        }
        tracker.markStopped(channel.id)
        logger.info(
            "WebSocket connection stopped for channel: ${channel.id} " +
                "(served ${holder.eventsReceived} event(s) over ${System.currentTimeMillis() - holder.startedAt}ms)",
        )
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

    /**
     * Kicks off the SDK connection on the listener's own thread.
     *
     * [FeishuWsTransport.start] is **not** blocking: it schedules the handshake and hands the socket to
     * the SDK's own ping loop, then returns. Two consequences shape this method:
     * - A clean return says nothing about liveness, so no connection state is claimed here. The
     *   first inbound event is the only positive proof, which is where [tracker.markConnected]
     *   runs (see [messageEventHandler]).
     * - The socket outlives this thread, so the holder must outlive it too. Retiring the holder
     *   here would leave the superseded-listener guard in [messageEventHandler] discarding every
     *   event the platform delivers, while the SDK kept the connection alive and healthy.
     *   Only [stop] retires a holder; a hard failure is reported and left for the reconcile loop
     *   to restart through the normal stop/start path.
     */
    private fun runConnection(
        holder: FeishuListener,
        transport: FeishuWsTransport,
    ) {
        val channelId = holder.channel.id
        if (holder.closing.get()) {
            // stop() ran while this thread was being scheduled; do not open a socket nobody owns.
            transport.close()
            listeners.remove(channelId, holder)
            return
        }
        try {
            transport.start()
            if (holder.closing.get()) {
                // stop() raced the kickoff: it closed a transport that had not opened yet, so the
                // socket started here is the live one and nothing else holds a reference to it.
                // Leaving it running means a zombie connection that auto-reconnects forever, and
                // Feishu cluster mode delivers each event to exactly one client — so a slice of
                // real traffic would silently go to a connection nobody serves.
                transport.close()
                listeners.remove(channelId, holder)
                return
            }
            logger.info("Feishu WebSocket connect scheduled for channel: $channelId, awaiting first event to confirm liveness")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!holder.closing.get()) tracker.markFailed(channelId, "interrupted")
        } catch (e: Throwable) {
            if (holder.closing.get()) return
            logger.error("WebSocket connection failed for channel: $channelId", e)
            tracker.markFailed(channelId, e.message ?: "ws client failure")
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
                        notifyUnreadable(holder.channel, sessionId, data.event?.message?.messageType)
                        // Committed, not rolled back: a redelivery of the same unreadable event must
                        // not send the user a second notice.
                        commit()
                        return@launchTurn
                    }
                    handler(channelMessage)
                    commit()
                } catch (e: Throwable) {
                    logger.error("Failed to handle Feishu message event for channel: $channelId", e)
                    rollback()
                }
            }
        }
    }

    /**
     * Tell the user their message type cannot be handled.
     *
     * Best-effort: an explanation that fails must not take the event thread down with it.
     */
    private fun notifyUnreadable(
        channel: ChannelSpec,
        sessionId: String,
        platformType: String?,
    ) {
        if (sessionId.isBlank()) {
            logger.warn("Feishu message type {} is unreadable and has no chat id to reply to", platformType)
            return
        }
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
        var error: Throwable? = null
        try {
            val response = httpClient.postJson(
                url,
                messageBody,
                mapOf(
                    "Authorization" to "Bearer $token",
                ),
            )
            handleSendResponse(response)
        } catch (e: Exception) {
            error = e
            throw e
        } finally {
            // Reported after the response has actually been checked: recording a rejected send as
            // a success is how the send-failure dashboard stayed green.
            metricsSink.onSendCompleted(channel.id, System.currentTimeMillis() - started, error)
        }
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
     * Parse Feishu event to ChannelMessage.
     *
     * Shared with [FeishuAdaptor.handleCallback] so the webhook and WebSocket transports agree on
     * what a delivered message means — including the image download that belongs to it.
     */
    internal suspend fun parseFeishuEvent(event: P2MessageReceiveV1, channel: ChannelSpec): ChannelMessage? {
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
                    // Forwarding the raw content JSON would hand the model something like
                    // {"file_key":"..."} as if the user had typed it. Refuse the message instead and
                    // let the caller tell the user, which is what the null return means here.
                    logger.info("Feishu message type {} is not readable for channel: $channelId", messageType)
                    return null
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
        // Base64 characters collected so far for this message's images.
        var imagePayloadChars = 0

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
                                if (imagePayloadChars >= MAX_AGGREGATE_IMAGE_CHARS) {
                                    // A post can carry twenty photos, and the per-image cap does not
                                    // bound the total. Their base64 all goes into one agent request,
                                    // so past the budget the images are dropped and the text still
                                    // gets answered.
                                    logger.warn(
                                        "Post for channel ${channel.id} passed the image payload budget; skipping image $imageKey",
                                    )
                                } else {
                                    val dataUrl = downloadFeishuImage(channel, imageKey)
                                    if (dataUrl != null) {
                                        imageUrls.add(dataUrl)
                                        imagePayloadChars += dataUrl.length
                                    } else {
                                        logger.warn("Failed to download image in post: $imageKey")
                                    }
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
     * The result is encoded as a data URL for downstream consumption.
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
            if (bytes == null || bytes.isEmpty()) {
                logger.warn("Empty response when downloading image: $imageKey")
                return null
            }
            if (bytes.size > MAX_INBOUND_IMAGE_BYTES) {
                // Base64 inflates by ~4/3 and the bytes go straight into the agent request, so an
                // oversized image is dropped with a log rather than blowing up the turn.
                logger.warn("Skipping oversized Feishu image $imageKey: {} bytes over the {} limit", bytes.size, MAX_INBOUND_IMAGE_BYTES)
                return null
            }
            val mime = sniffImageMimeType(bytes, imageKey) ?: return null
            "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
        } catch (e: Exception) {
            logger.error("Failed to download image $imageKey for channel ${channel.id}: ${e.message}", e)
            null
        }
    }

    /**
     * Guess the image type from its leading bytes, or null for anything unrecognised.
     *
     * The image API hands back raw binary with no type this client can read, and labelling every
     * image `image/jpeg` — what this used to do — made a PNG arrive at the model as a JPEG it
     * cannot decode. An unknown payload is *skipped* rather than passed on as
     * `application/octet-stream`: an unsupported media type makes the whole agent turn fail, while
     * dropping one image only costs the text reply its attachment. HEIC and AVIF land here — iPhone
     * cameras produce them and Feishu forwards them unchanged.
     */
    private fun sniffImageMimeType(
        bytes: ByteArray,
        imageKey: String,
    ): String? {
        fun startsWith(prefix: ByteArray): Boolean = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
        return when {
            startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "image/jpeg"
            startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
            startsWith("GIF8".toByteArray(Charsets.US_ASCII)) -> "image/gif"
            startsWith("BM".toByteArray(Charsets.US_ASCII)) -> "image/bmp"
            bytes.size >= 12 &&
                startsWith("RIFF".toByteArray(Charsets.US_ASCII)) &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> {
                logger.warn("Unrecognised image format for $imageKey, skipping it rather than sending an unsupported type")
                null
            }
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

        // Ceiling for an inbound image before it is base64'd into the agent request.
        private const val MAX_INBOUND_IMAGE_BYTES = 5L * 1024 * 1024

        // Ceiling for the base64 of *all* images in one message: a single per-image cap leaves a
        // multi-photo post unbounded, and the whole set is carried in one agent request.
        private const val MAX_AGGREGATE_IMAGE_CHARS = 8 * 1024 * 1024
    }
}
