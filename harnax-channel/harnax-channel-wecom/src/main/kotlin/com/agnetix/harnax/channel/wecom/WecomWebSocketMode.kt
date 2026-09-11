package com.agnetix.harnax.channel.wecom

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
import com.agnetix.harnax.channel.sdk.util.ReconnectBackoff
import com.agnetix.harnax.channel.sdk.util.TextChunker
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * WeChat Work (WeCom) smart-robot WebSocket Long Connection Mode.
 *
 * There is no official Java SDK for the smart-robot long connection, so this
 * implements the protocol directly over OkHttp WebSocket, mirroring
 * cc-connect's platform/wecom/websocket.go:
 * - Connect to wss://openws.work.weixin.qq.com
 * - Authenticate by sending an `aibot_subscribe` frame (bot_id + secret)
 * - Heartbeat: send `ping` every 30s; after [MAX_MISSED_PONG] consecutive
 *   pings without an ack, treat the connection as dead and reconnect
 * - Reconnect with exponential backoff (1s → 30s), reset when a connection
 *   was alive long enough (see [ReconnectBackoff])
 * - Message dedup by msgid to tolerate at-least-once delivery
 *
 * Replies use `aibot_respond_msg` (stream format, full replacement) keyed by the
 * original callback req_id; proactive sends use `aibot_send_msg` (markdown).
 *
 * Lifecycle and resource notes:
 * - Every connect attempt carries a generation number. OkHttp callbacks from a socket of
 *   an older attempt are ignored: without this, a late onClosed from a socket we already
 *   replaced would trigger an extra reconnect and cancel the new connection's heartbeat.
 * - The OkHttp client and the heartbeat scheduler are shared across channels. Each
 *   connection used to own both, so N channels meant N connection pools and N threads
 *   that spent their life sleeping between pings.
 * - Message handling is delegated to [ChannelTurnExecutor], which bounds in-flight agent
 *   turns per channel and serializes turns belonging to the same conversation.
 */
class WecomWebSocketMode(
    private val turnExecutor: ChannelTurnExecutor = ChannelTurnExecutor.SHARED,
    private val metricsSink: ChannelMetricsSink = NoOpChannelMetricsSink,
) : ChannelCommunicationMode {

    private val logger = LoggerFactory.getLogger(WecomWebSocketMode::class.java)
    private val objectMapper = jacksonObjectMapper()

    private val connections = ConcurrentHashMap<Long, Connection>()
    private val deduplicators = ConcurrentHashMap<Long, MessageDeduplicator>()
    private val tracker = ChannelConnectionTracker(metricsSink, ChannelType.WECOM.code)

    /** sessionId(chatId) -> latest callback req_id, per channel, used to build replies. */
    private val replyReqIds = ConcurrentHashMap<Long, ConcurrentHashMap<String, String>>()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // The platform protocol defines its own ping frame; OkHttp-level pings would
        // not be understood by the peer and would only mask heartbeat failures.
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket: liveness is heartbeat-based
        .build()

    private val scheduler: ScheduledExecutorService by lazy {
        val threads = (Runtime.getRuntime().availableProcessors().coerceIn(1, 2) + 1)
        Executors.newScheduledThreadPool(threads) { runnable ->
            Thread(runnable, "wecom-ws-timer").apply { isDaemon = true }
        }
    }

    /** Per-channel connection state. */
    private inner class Connection(val channel: ChannelSpec) {
        val reqSeq = AtomicLong(0)
        val missedPong = AtomicInteger(0)
        val stopped = AtomicBoolean(false)

        /** Incremented on every connect attempt; identifies which socket is authoritative. */
        val generation = AtomicLong(0)

        @Volatile
        var webSocket: WebSocket? = null

        @Volatile
        var connectedAt: Long = 0

        @Volatile
        var messageHandler: (suspend (ChannelMessage) -> Unit)? = null

        val backoff = ReconnectBackoff()

        // Cancellable handle for the current heartbeat task, so a reconnect can
        // cancel the previous one instead of leaking stale periodic tasks.
        val heartbeatFuture = AtomicReference<ScheduledFuture<*>?>(null)

        fun isCurrent(gen: Long): Boolean = !stopped.get() && generation.get() == gen

        fun nextReqId(prefix: String): String = "${prefix}_${reqSeq.incrementAndGet()}"
    }

    override fun getModeName(): String = "websocket"

    override fun isCallbackMode(): Boolean = false

    override fun connectionState(channelId: Long): ChannelConnectionState = tracker.state(channelId)

    override fun connectionStates(): List<ChannelConnectionState> = tracker.snapshot()

    override fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        if (connections.containsKey(channel.id)) {
            logger.warn("WeCom WebSocket connection already exists for channel: ${channel.id}")
            return
        }
        val botId = channel.appId
        val secret = channel.appSecret
        if (botId.isNullOrBlank() || secret.isNullOrBlank()) {
            tracker.markFailed(channel.id, "missing bot_id or bot_secret")
            throw IllegalArgumentException("WeCom WebSocket mode requires appId(bot_id) and appSecret(bot_secret) for channel: ${channel.id}")
        }

        val conn = Connection(channel)
        conn.messageHandler = messageHandler
        connections[channel.id] = conn
        logger.info("Starting WeCom WebSocket connection for channel: ${channel.id}")
        connect(conn)
    }

    override fun stop(channel: ChannelSpec) {
        val conn = connections.remove(channel.id)
        deduplicators.remove(channel.id)
        replyReqIds.remove(channel.id)
        if (conn == null) {
            tracker.markStopped(channel.id)
            logger.warn("No WeCom WebSocket connection found for channel: ${channel.id}")
            return
        }
        conn.stopped.set(true)
        conn.heartbeatFuture.getAndSet(null)?.cancel(false)
        conn.webSocket?.close(1000, "client stop")
        conn.webSocket = null
        tracker.markStopped(channel.id)
        logger.info("WeCom WebSocket connection stopped for channel: ${channel.id}")
    }

    /** Stop every channel and release the shared transport resources (application shutdown). */
    fun shutdown() {
        connections.values.forEach { conn ->
            conn.stopped.set(true)
            conn.heartbeatFuture.getAndSet(null)?.cancel(false)
            runCatching { conn.webSocket?.close(1000, "shutdown") }
        }
        connections.clear()
        deduplicators.clear()
        replyReqIds.clear()
        scheduler.shutdownNow()
        runCatching { httpClient.dispatcher.executorService.shutdown() }
        runCatching { httpClient.connectionPool.evictAll() }
    }

    // ==================== Connection lifecycle ====================

    private fun connect(conn: Connection) {
        if (conn.stopped.get()) return
        val channelId = conn.channel.id
        val gen = conn.generation.incrementAndGet()
        tracker.markConnecting(channelId)
        val request = Request.Builder().url(WecomFrames.ENDPOINT).build()
        val botId = conn.channel.appId!!
        val secret = conn.channel.appSecret!!

        try {
            httpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (!conn.isCurrent(gen)) {
                            logger.info("Ignoring onOpen from a superseded WeCom socket, channel: $channelId")
                            runCatching { webSocket.close(1000, "superseded") }
                            return
                        }
                        conn.webSocket = webSocket
                        conn.connectedAt = System.currentTimeMillis()
                        conn.missedPong.set(0)
                        logger.info("WeCom WebSocket opened for channel: $channelId, subscribing")
                        writeFrame(conn, webSocket, WecomFrames.subscribe(conn.nextReqId(WecomFrames.CMD_SUBSCRIBE), botId, secret))
                        startHeartbeat(conn, webSocket, gen)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (!conn.isCurrent(gen)) return
                        handleRawFrame(conn, text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        logger.warn("WeCom WebSocket failure for channel: $channelId: ${t.message}")
                        scheduleReconnect(conn, gen, t.message ?: "websocket failure")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        logger.info("WeCom WebSocket closed for channel: $channelId, code=$code, reason=$reason")
                        scheduleReconnect(conn, gen, "closed code=$code")
                    }
                },
            )
        } catch (e: Exception) {
            logger.error("WeCom WebSocket connect failed to initiate for channel: $channelId: ${e.message}", e)
            tracker.markFailed(channelId, e.message ?: "connect failed")
            scheduleReconnect(conn, gen, e.message ?: "connect failed")
        }
    }

    private fun scheduleReconnect(
        conn: Connection,
        gen: Long,
        reason: String,
    ) {
        if (!conn.isCurrent(gen)) {
            logger.debug("Ignoring reconnect request from a stale WeCom socket, channel: ${conn.channel.id}")
            return
        }
        // Cancel the heartbeat task tied to the dead connection.
        conn.heartbeatFuture.getAndSet(null)?.cancel(false)
        conn.webSocket = null

        val alive = if (conn.connectedAt > 0) {
            Duration.ofMillis(System.currentTimeMillis() - conn.connectedAt)
        } else {
            Duration.ZERO
        }
        conn.connectedAt = 0

        val delay = conn.backoff.onDisconnected(alive)
        tracker.markReconnecting(conn.channel.id, reason)
        logger.warn("WeCom WebSocket reconnecting for channel: ${conn.channel.id} in ${delay.toMillis()}ms (reason=$reason)")
        try {
            scheduler.schedule({ connect(conn) }, delay.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            logger.error("WeCom reconnect could not be scheduled for channel: ${conn.channel.id}: ${e.message}")
            tracker.markFailed(conn.channel.id, "reconnect not scheduled: ${e.message}")
        }
    }

    private fun startHeartbeat(
        conn: Connection,
        webSocket: WebSocket,
        gen: Long,
    ) {
        val future = scheduler.scheduleAtFixedRate({
            if (!conn.isCurrent(gen) || conn.webSocket !== webSocket) {
                return@scheduleAtFixedRate
            }
            if (conn.missedPong.get() >= MAX_MISSED_PONG) {
                logger.warn("WeCom WebSocket no heartbeat ack for channel: ${conn.channel.id}, closing")
                tracker.markReconnecting(conn.channel.id, "heartbeat timeout")
                runCatching { webSocket.close(4000, "heartbeat timeout") }
                return@scheduleAtFixedRate
            }
            conn.missedPong.incrementAndGet()
            writeFrame(conn, webSocket, WecomFrames.ping(conn.nextReqId(WecomFrames.CMD_PING)))
        }, PING_INTERVAL_SEC, PING_INTERVAL_SEC, TimeUnit.SECONDS)
        // Replace and cancel any previous heartbeat task from an earlier connection.
        conn.heartbeatFuture.getAndSet(future)?.cancel(false)
    }

    // ==================== Frame handling ====================

    private fun handleRawFrame(
        conn: Connection,
        raw: String,
    ) {
        val node = try {
            objectMapper.readTree(raw)
        } catch (e: Exception) {
            logger.warn("WeCom WebSocket invalid json for channel: ${conn.channel.id}: ${e.message}")
            return
        }
        val cmd = node.path("cmd").asText("")
        val reqId = node.path("headers").path("req_id").asText("")

        when (cmd) {
            WecomFrames.CMD_MSG_CALLBACK -> handleMsgCallback(conn, reqId, node.path("body"))
            WecomFrames.CMD_EVENT_CALLBACK -> logger.debug("WeCom event callback ignored, channel: ${conn.channel.id}")
            "" -> handleResponseFrame(conn, reqId, node)
            else -> logger.debug("WeCom unhandled cmd '$cmd' for channel: ${conn.channel.id}")
        }
    }

    /** Response frames carry no cmd and are identified by their req_id prefix. */
    private fun handleResponseFrame(
        conn: Connection,
        reqId: String,
        node: JsonNode,
    ) {
        val channelId = conn.channel.id
        when {
            reqId.startsWith(WecomFrames.CMD_PING) -> {
                conn.missedPong.set(0)
                tracker.markHeartbeat(channelId)
                logger.debug("WeCom heartbeat ack, channel: $channelId")
            }

            reqId.startsWith(WecomFrames.CMD_SUBSCRIBE) -> {
                val errcode = node.path("errcode").asInt(-1)
                if (errcode == 0) {
                    conn.missedPong.set(0)
                    tracker.markConnected(channelId)
                    logger.info("WeCom subscribed successfully, channel: $channelId")
                } else {
                    val errmsg = node.path("errmsg").asText("")
                    logger.error("WeCom subscribe failed, channel: $channelId, errcode=$errcode, errmsg=$errmsg")
                    tracker.markFailed(channelId, "subscribe errcode=$errcode errmsg=$errmsg")
                }
            }

            else -> {
                val errcode = node.path("errcode").asInt(0)
                if (errcode != 0) {
                    val detail = node.path("errmsg").asText("")
                    logger.warn("WeCom send/reply ack error, channel: $channelId, req_id=$reqId, errcode=$errcode, errmsg=$detail")
                    metricsSink.onSendCompleted(
                        channelId,
                        0,
                        ChannelSendException(ChannelType.WECOM, errcode.toString(), "WeCom ack error: $detail"),
                    )
                } else {
                    logger.debug("WeCom send/reply ack ok, channel: $channelId, req_id=$reqId")
                    metricsSink.onSendCompleted(channelId, 0, null)
                }
            }
        }
    }

    private fun handleMsgCallback(
        conn: Connection,
        reqId: String,
        body: JsonNode,
    ) {
        val channelId = conn.channel.id
        val msgId = body.path("msgid").asText("")
        val dedup = if (msgId.isBlank()) null else deduplicators.computeIfAbsent(channelId) { MessageDeduplicator() }
        if (dedup != null && !dedup.tryBegin(msgId)) {
            logger.debug("WeCom duplicate message ignored: msgId=$msgId, channel=$channelId")
            tracker.onDuplicateMessage(channelId)
            return
        }

        try {
            val msgType = body.path("msgtype").asText("")
            val userId = body.path("from").path("userid").asText("")
            val chatType = body.path("chattype").asText("")
            var chatId = body.path("chatid").asText("")
            if (chatId.isBlank()) chatId = userId

            val text = when (msgType) {
                "text" -> body.path("text").path("content").asText("")
                "voice" -> {
                    val c = body.path("voice").path("content").asText("")
                    if (c.isNotBlank()) c else body.path("voice").path("text").asText("")
                }

                else -> ""
            }.trim()

            if (text.isEmpty()) {
                logger.debug("WeCom message without text ignored (msgtype={}), channel={}", msgType, channelId)
                dedup?.rollback(msgId)
                return
            }

            val isGroup = chatType == "group"
            val sessionId = chatId
            // Cache the callback req_id so Reply() (aibot_respond_msg) can address it.
            if (reqId.isNotBlank()) {
                val submap = replyReqIds.computeIfAbsent(channelId) { ConcurrentHashMap() }
                if (submap.size >= MAX_REPLY_REQ_IDS) submap.clear()
                submap[sessionId] = reqId
            }

            val channelMessage = ChannelMessage.builder()
                .messageId(msgId)
                .sessionId(sessionId)
                .messageType(MessageType.TEXT)
                .content(text)
                .channelType(ChannelType.WECOM)
                .senderId(userId)
                .senderName(userId)
                .isGroupMessage(isGroup)
                .groupId(if (isGroup) chatId else null)
                .build()

            tracker.markMessageReceived(channelId)
            dispatch(conn, channelMessage, dedup, msgId)
        } catch (e: Exception) {
            logger.error("Failed to process WeCom callback frame for channel: $channelId", e)
            dedup?.rollback(msgId)
        }
    }

    private fun dispatch(
        conn: Connection,
        message: ChannelMessage,
        dedup: MessageDeduplicator?,
        msgId: String,
    ) {
        val handler = conn.messageHandler
        if (handler == null) {
            logger.warn("No message handler registered for channel: ${conn.channel.id}")
            dedup?.rollback(msgId)
            return
        }
        turnExecutor.launchTurn(conn.channel.id, message.sessionId) {
            try {
                handler(message)
                // Only a completed turn may suppress the platform's next redelivery.
                dedup?.commit(msgId)
            } catch (e: Exception) {
                logger.error("Failed to handle WeCom message for channel: ${conn.channel.id}", e)
                dedup?.rollback(msgId)
            }
        }
    }

    // ==================== Sending ====================

    override suspend fun sendMessage(
        channel: ChannelSpec,
        sessionId: String,
        message: String,
    ) {
        val conn = connections[channel.id] ?: throw notConnected(channel)
        val webSocket = conn.webSocket ?: throw notConnected(channel)
        val started = System.currentTimeMillis()

        val result = runCatching {
            // Prefer replying to the original message (aibot_respond_msg) when we have a req_id.
            val reqId = replyReqIds[channel.id]?.get(sessionId)
            if (reqId != null) {
                val streamId = conn.nextReqId("stream")
                requireSent(conn, webSocket, WecomFrames.respondMsg(reqId, streamId, message))
                logger.debug("WeCom reply sent for channel: ${channel.id}, session=$sessionId")
            } else {
                // Fall back to proactive send (aibot_send_msg) in markdown, chunked.
                val chunks = TextChunker.splitByChars(message, MAX_SEND_CHUNK_CHARS)
                for (chunk in chunks) {
                    requireSent(conn, webSocket, WecomFrames.sendMsg(conn.nextReqId(WecomFrames.CMD_SEND_MSG), sessionId, chunk))
                }
                logger.debug("WeCom proactive message sent for channel: ${channel.id}, chunks=${chunks.size}")
            }
        }

        metricsSink.onSendCompleted(channel.id, System.currentTimeMillis() - started, result.exceptionOrNull())
        result.getOrThrow()
    }

    override suspend fun sendRichMessage(
        channel: ChannelSpec,
        sessionId: String,
        richMessage: RichMessage,
    ) {
        val text = when (richMessage) {
            is com.agnetix.harnax.channel.sdk.message.TextRichMessage -> richMessage.content
            is com.agnetix.harnax.channel.sdk.message.MarkdownRichMessage -> richMessage.content
            else -> richMessage.toString()
        }
        sendMessage(channel, sessionId, text)
    }

    private fun notConnected(channel: ChannelSpec) = ChannelSendException(
        channelType = ChannelType.WECOM,
        platformErrorCode = null,
        message = "WeCom WebSocket is not connected for channel: ${channel.id}",
    )

    // ==================== Helpers ====================

    /** @return false when OkHttp refused the frame (outbound queue full or socket closing). */
    private fun writeFrame(
        conn: Connection,
        webSocket: WebSocket,
        frame: Map<String, Any>,
    ): Boolean = try {
        val queued = webSocket.send(objectMapper.writeValueAsString(frame))
        if (!queued) {
            logger.warn("WeCom WebSocket rejected a frame under backpressure, channel: ${conn.channel.id}")
        }
        queued
    } catch (e: Exception) {
        logger.warn("WeCom frame write failed for channel: ${conn.channel.id}: ${e.message}", e)
        false
    }

    private fun requireSent(
        conn: Connection,
        webSocket: WebSocket,
        frame: Map<String, Any>,
    ) {
        if (!writeFrame(conn, webSocket, frame)) {
            throw ChannelSendException(
                channelType = ChannelType.WECOM,
                platformErrorCode = null,
                message = "WeCom WebSocket could not enqueue the message for channel: ${conn.channel.id}",
            )
        }
    }

    companion object {
        private const val PING_INTERVAL_SEC = 30L
        private const val MAX_MISSED_PONG = 2
        private const val MAX_SEND_CHUNK_CHARS = 2000

        // Bound the per-channel replyReqIds cache. reqIds are ephemeral (only the
        // latest per active session is useful); when exceeded we clear the map and
        // rarely-active sessions simply fall back to the proactive send path.
        private const val MAX_REPLY_REQ_IDS = 5000
    }
}
