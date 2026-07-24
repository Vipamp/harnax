package com.agnetix.harnax.channel.wecom

import com.agnetix.harnax.channel.sdk.adaptor.ChannelCommunicationMode
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.error.ChannelSendException
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageType
import com.agnetix.harnax.channel.sdk.message.RichMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Collections
import java.util.LinkedHashSet
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
 */
class WecomWebSocketMode : ChannelCommunicationMode {

    private val logger = LoggerFactory.getLogger(WecomWebSocketMode::class.java)
    private val objectMapper = jacksonObjectMapper()

    private val connections = ConcurrentHashMap<Long, Connection>()
    private val messageHandlers = ConcurrentHashMap<Long, suspend (ChannelMessage) -> Unit>()
    private val processedMessageIds = ConcurrentHashMap<Long, MutableSet<String>>()

    /** sessionId(chatId) -> latest callback req_id, per channel, used to build replies. */
    private val replyReqIds = ConcurrentHashMap<Long, ConcurrentHashMap<String, String>>()

    companion object {
        private const val PING_INTERVAL_SEC = 30L
        private const val MAX_MISSED_PONG = 2
        private const val MAX_PROCESSED_IDS = 1000
        private const val MAX_SEND_CHUNK_CHARS = 2000

        // Bound the per-channel replyReqIds cache. reqIds are ephemeral (only the
        // latest per active session is useful); when exceeded we clear the map and
        // rarely-active sessions simply fall back to the proactive send path.
        private const val MAX_REPLY_REQ_IDS = 5000
    }

    /** Per-channel connection state. */
    private inner class Connection(val channel: ChannelSpec) {
        val reqSeq = AtomicLong(0)
        val missedPong = AtomicInteger(0)
        val stopped = AtomicBoolean(false)

        @Volatile var webSocket: WebSocket? = null

        @Volatile var connectedAt: Long = 0
        val client: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.SECONDS) // we manage app-level ping ourselves
            .build()
        val scheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "wecom-ws-channel-${channel.id}").apply { isDaemon = true }
            }
        val backoff = ReconnectBackoff()

        // Cancellable handle for the current heartbeat task, so a reconnect can
        // cancel the previous one instead of leaking stale periodic tasks.
        val heartbeatFuture = AtomicReference<ScheduledFuture<*>?>(null)

        // Ensures only one reconnect is scheduled per disconnect, since OkHttp
        // may fire both onFailure and onClosed for a single connection drop.
        val reconnectScheduled = AtomicBoolean(false)

        // Handles agent processing off the WebSocket reader thread so that a
        // long-running turn never blocks heartbeat ACK reads (which would
        // otherwise trip the missed-pong watchdog and drop a live connection).
        val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun nextReqId(prefix: String): String = "${prefix}_${reqSeq.incrementAndGet()}"
    }

    override fun getModeName(): String = "websocket"

    override fun isCallbackMode(): Boolean = false

    override fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        if (connections.containsKey(channel.id)) {
            logger.warn("WeCom WebSocket connection already exists for channel: ${channel.id}")
            return
        }
        val botId = channel.appId
        val secret = channel.appSecret
        if (botId.isNullOrBlank() || secret.isNullOrBlank()) {
            throw IllegalArgumentException("WeCom WebSocket mode requires appId(bot_id) and appSecret(bot_secret) for channel: ${channel.id}")
        }

        val conn = Connection(channel)
        connections[channel.id] = conn
        messageHandlers[channel.id] = messageHandler
        logger.info("Starting WeCom WebSocket connection for channel: ${channel.id}")
        connect(conn)
    }

    override fun stop(channel: ChannelSpec) {
        val conn = connections.remove(channel.id)
        messageHandlers.remove(channel.id)
        processedMessageIds.remove(channel.id)
        replyReqIds.remove(channel.id)
        if (conn != null) {
            conn.stopped.set(true)
            conn.webSocket?.close(1000, "client stop")
            conn.scheduler.shutdownNow()
            conn.client.dispatcher.executorService.shutdown()
            conn.handlerScope.cancel()
            logger.info("WeCom WebSocket connection stopped for channel: ${channel.id}")
        } else {
            logger.warn("No WeCom WebSocket connection found for channel: ${channel.id}")
        }
    }

    // ==================== Connection lifecycle ====================

    private fun connect(conn: Connection) {
        if (conn.stopped.get()) return
        // A new attempt is starting; allow the next disconnect to schedule a reconnect.
        conn.reconnectScheduled.set(false)
        val request = Request.Builder().url(WecomFrames.ENDPOINT).build()
        val botId = conn.channel.appId!!
        val secret = conn.channel.appSecret!!

        conn.client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    conn.webSocket = webSocket
                    conn.connectedAt = System.currentTimeMillis()
                    conn.missedPong.set(0)
                    logger.info("WeCom WebSocket opened for channel: ${conn.channel.id}, subscribing")
                    val reqId = conn.nextReqId(WecomFrames.CMD_SUBSCRIBE)
                    writeFrame(webSocket, WecomFrames.subscribe(reqId, botId, secret))
                    startHeartbeat(conn, webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleRawFrame(conn, text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    logger.warn("WeCom WebSocket failure for channel: ${conn.channel.id}: ${t.message}")
                    scheduleReconnect(conn)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    logger.info("WeCom WebSocket closed for channel: ${conn.channel.id}, code=$code, reason=$reason")
                    scheduleReconnect(conn)
                }
            },
        )
    }

    private fun scheduleReconnect(conn: Connection) {
        if (conn.stopped.get()) return
        // OkHttp may fire both onFailure and onClosed for one disconnect; only
        // schedule a single reconnect.
        if (!conn.reconnectScheduled.compareAndSet(false, true)) return

        // Cancel the heartbeat task tied to the dead connection.
        conn.heartbeatFuture.getAndSet(null)?.cancel(false)

        val alive = if (conn.connectedAt > 0) {
            java.time.Duration.ofMillis(System.currentTimeMillis() - conn.connectedAt)
        } else {
            java.time.Duration.ZERO
        }
        conn.connectedAt = 0
        conn.webSocket = null
        val delay = conn.backoff.onDisconnected(alive)
        logger.warn("WeCom WebSocket reconnecting for channel: ${conn.channel.id} in ${delay.toMillis()}ms")
        try {
            conn.scheduler.schedule({ connect(conn) }, delay.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            logger.debug("WeCom reconnect not scheduled (scheduler shut down) for channel: ${conn.channel.id}")
        }
    }

    private fun startHeartbeat(conn: Connection, webSocket: WebSocket) {
        val future = conn.scheduler.scheduleAtFixedRate({
            if (conn.stopped.get()) return@scheduleAtFixedRate
            if (conn.webSocket !== webSocket) return@scheduleAtFixedRate // stale
            if (conn.missedPong.get() >= MAX_MISSED_PONG) {
                logger.warn("WeCom WebSocket no heartbeat ack for channel: ${conn.channel.id}, closing")
                webSocket.close(4000, "heartbeat timeout")
                return@scheduleAtFixedRate
            }
            conn.missedPong.incrementAndGet()
            writeFrame(webSocket, WecomFrames.ping(conn.nextReqId(WecomFrames.CMD_PING)))
        }, PING_INTERVAL_SEC, PING_INTERVAL_SEC, TimeUnit.SECONDS)
        // Replace and cancel any previous heartbeat task from an earlier connection.
        conn.heartbeatFuture.getAndSet(future)?.cancel(false)
    }

    // ==================== Frame handling ====================

    private fun handleRawFrame(conn: Connection, raw: String) {
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
            "" -> {
                // Response frame (no cmd): identify by req_id prefix.
                when {
                    reqId.startsWith(WecomFrames.CMD_PING) -> {
                        conn.missedPong.set(0)
                        logger.debug("WeCom heartbeat ack, channel: ${conn.channel.id}")
                    }
                    reqId.startsWith(WecomFrames.CMD_SUBSCRIBE) -> {
                        val errcode = node.path("errcode").asInt(-1)
                        if (errcode == 0) {
                            conn.missedPong.set(0)
                            logger.info("WeCom subscribed successfully, channel: ${conn.channel.id}")
                        } else {
                            logger.error(
                                "WeCom subscribe failed, channel: ${conn.channel.id}, errcode=$errcode, errmsg=${node.path("errmsg").asText("")}",
                            )
                        }
                    }
                    else -> {
                        val errcode = node.path("errcode").asInt(0)
                        if (errcode != 0) {
                            logger.warn(
                                "WeCom send/reply ack error, channel: ${conn.channel.id}, req_id=$reqId, errcode=$errcode, errmsg=${node.path("errmsg").asText("")}",
                            )
                        } else {
                            logger.debug("WeCom send/reply ack ok, channel: ${conn.channel.id}, req_id=$reqId")
                        }
                    }
                }
            }
            else -> logger.debug("WeCom unhandled cmd '$cmd' for channel: ${conn.channel.id}")
        }
    }

    private fun handleMsgCallback(conn: Connection, reqId: String, body: JsonNode) {
        val msgId = body.path("msgid").asText("")
        if (msgId.isNotBlank() && !markMessageProcessed(conn.channel.id, msgId)) {
            logger.debug("WeCom duplicate message ignored: msgId=$msgId, channel=${conn.channel.id}")
            return
        }

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
            logger.debug("WeCom message without text ignored (msgtype={}), channel={}", msgType, conn.channel.id)
            return
        }

        val isGroup = chatType == "group"
        val sessionId = chatId
        // Cache the callback req_id so Reply() (aibot_respond_msg) can address it.
        if (reqId.isNotBlank()) {
            val submap = replyReqIds.computeIfAbsent(conn.channel.id) { ConcurrentHashMap() }
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

        conn.handlerScope.launch {
            try {
                messageHandlers[conn.channel.id]?.invoke(channelMessage)
                    ?: logger.warn("No message handler registered for channel: ${conn.channel.id}")
            } catch (e: Exception) {
                logger.error("Failed to handle WeCom message for channel: ${conn.channel.id}", e)
            }
        }
    }

    // ==================== Sending ====================

    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        val conn = connections[channel.id] ?: throw notConnected(channel)
        val webSocket = conn.webSocket ?: throw notConnected(channel)

        // Prefer replying to the original message (aibot_respond_msg) when we have a req_id.
        val reqId = replyReqIds[channel.id]?.get(sessionId)
        if (reqId != null) {
            val streamId = conn.nextReqId("stream")
            writeFrame(webSocket, WecomFrames.respondMsg(reqId, streamId, message))
            logger.debug("WeCom reply sent for channel: ${channel.id}, session=$sessionId")
            return
        }

        // Fall back to proactive send (aibot_send_msg) in markdown, chunked.
        val chunks = splitByChars(message, MAX_SEND_CHUNK_CHARS)
        for (chunk in chunks) {
            writeFrame(webSocket, WecomFrames.sendMsg(conn.nextReqId(WecomFrames.CMD_SEND_MSG), sessionId, chunk))
        }
        logger.debug("WeCom proactive message sent for channel: ${channel.id}, chunks=${chunks.size}")
    }

    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
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

    private fun writeFrame(webSocket: WebSocket, frame: Map<String, Any>) {
        webSocket.send(objectMapper.writeValueAsString(frame))
    }

    private fun splitByChars(message: String, maxChars: Int): List<String> {
        if (message.length <= maxChars) return listOf(message)
        val chunks = mutableListOf<String>()
        var remaining = message
        while (remaining.length > maxChars) {
            var splitAt = remaining.lastIndexOf('\n', maxChars)
            if (splitAt <= 0) splitAt = remaining.lastIndexOf(' ', maxChars)
            if (splitAt <= 0) splitAt = maxChars
            chunks.add(remaining.substring(0, splitAt))
            remaining = remaining.substring(splitAt).trimStart()
        }
        if (remaining.isNotEmpty()) chunks.add(remaining)
        return chunks
    }

    private fun markMessageProcessed(channelId: Long, messageId: String): Boolean {
        val ids = processedMessageIds.computeIfAbsent(channelId) {
            Collections.synchronizedSet(LinkedHashSet())
        }
        synchronized(ids) {
            if (ids.contains(messageId)) return false
            ids.add(messageId)
            if (ids.size > MAX_PROCESSED_IDS) {
                val iterator = ids.iterator()
                var toRemove = ids.size - MAX_PROCESSED_IDS / 2
                while (iterator.hasNext() && toRemove > 0) {
                    iterator.next()
                    iterator.remove()
                    toRemove--
                }
            }
            return true
        }
    }
}
