package com.agnetix.harnax.channel.dingtalk

import com.agnetix.harnax.channel.dingtalk.client.PlatformHttpClient
import com.agnetix.harnax.channel.dingtalk.client.PlatformResponse
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
import com.dingtalk.open.app.api.OpenDingTalkClient
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener
import com.dingtalk.open.app.api.models.bot.ChatbotMessage
import com.dingtalk.open.app.api.security.AuthClientCredential
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DingTalk Stream Long Connection Mode.
 *
 * Implements a WebSocket-based full-duplex connection using the DingTalk official
 * Stream SDK (com.dingtalk.open:app-stream-client). Mirrors the design of
 * [com.agnetix.harnax.channel.feishu.FeishuWebSocketMode]:
 * - No public IP or callback URL required, only outbound network access.
 * - Authentication (clientId/clientSecret), heartbeat and auto-reconnect are
 *   handled internally by the SDK.
 * - Message deduplication based on msgId to tolerate at-least-once delivery.
 *
 * Sending strategy:
 * - Each incoming bot message carries a temporary `sessionWebhook` (valid until
 *   `sessionWebhookExpiredTime`). Replies are POSTed to that webhook. Because the
 *   SDK contract sends by (channel, sessionId), we cache the latest webhook per
 *   conversationId so [sendMessage] can resolve it.
 * - Once that webhook has expired (e.g. the agent took longer than its TTL, or the
 *   reply is triggered by something other than an inbound message), sending degrades to
 *   the robot OpenAPI (`oToMessages/batchSend` / `groupMessages/send`) using the
 *   conversation metadata cached from the inbound message, instead of failing outright.
 *
 * Lifecycle notes:
 * - One [Listener] holder per channel carries the client, the connect thread and the
 *   closing flag, replacing three maps that could disagree during a restart.
 * - Inbound messages are dispatched to [ChannelTurnExecutor]: bounded per-channel
 *   concurrency, and turns of the same conversation run in arrival order.
 */
class DingtalkStreamMode(
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
    private val turnExecutor: ChannelTurnExecutor = ChannelTurnExecutor.SHARED,
    private val metricsSink: ChannelMetricsSink = NoOpChannelMetricsSink,
) : ChannelCommunicationMode {

    private val logger = LoggerFactory.getLogger(DingtalkStreamMode::class.java)
    private val objectMapper = jacksonObjectMapper()

    private val tracker = ChannelConnectionTracker(metricsSink, ChannelType.DINGTALK.code)

    private val listeners = ConcurrentHashMap<Long, Listener>()
    private val deduplicators = ConcurrentHashMap<Long, MessageDeduplicator>()

    /** conversationId -> latest sessionWebhook, per channel. */
    private val sessionWebhooks = ConcurrentHashMap<Long, ConcurrentHashMap<String, WebhookEntry>>()

    /** conversationId -> where to send proactively once the session webhook is gone. */
    private val proactiveTargets = ConcurrentHashMap<Long, ConcurrentHashMap<String, ProactiveTarget>>()

    /** appId -> robot access token. */
    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    private data class WebhookEntry(val url: String, val expireAt: Long)

    private data class ProactiveTarget(val robotCode: String, val isGroup: Boolean, val conversationId: String, val userId: String?)

    private data class CachedToken(val token: String, val expiresAt: Long)

    private inner class Listener(val channel: ChannelSpec) {
        val closing = AtomicBoolean(false)

        @Volatile
        var client: OpenDingTalkClient? = null

        @Volatile
        var thread: Thread? = null

        @Volatile
        var messageHandler: (suspend (ChannelMessage) -> Unit)? = null
    }

    companion object {
        // DingTalk single message content limit is 20000 chars; keep headroom for markdown wrapper.
        private const val MAX_MESSAGE_CHUNK_CHARS = 18_000

        private const val TOKEN_REFRESH_SAFETY_SEC = 300L

        private const val STOP_JOIN_MS = 3_000L

        // Bound the per-channel proactive-target cache. Entries are refreshed by every
        // inbound message, so clearing wholesale only costs a rare fallback re-learn.
        private const val MAX_PROACTIVE_TARGETS = 5_000

        private const val API_BASE = "https://api.dingtalk.com"
    }

    override fun getModeName(): String = "stream"

    override fun isCallbackMode(): Boolean = false

    override fun connectionState(channelId: Long): ChannelConnectionState = tracker.state(channelId)

    override fun connectionStates(): List<ChannelConnectionState> = tracker.snapshot()

    override fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        val existing = listeners[channel.id]
        if (existing != null && !existing.closing.get()) {
            logger.warn("Stream connection already exists for channel: ${channel.id}")
            return
        }

        val clientId = channel.appId
        val clientSecret = channel.appSecret
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            tracker.markFailed(channel.id, "missing appId(clientId) or appSecret(clientSecret)")
            throw IllegalArgumentException("Stream mode requires appId(clientId) and appSecret(clientSecret) for channel: ${channel.id}")
        }

        val holder = Listener(channel)
        holder.messageHandler = messageHandler
        if (listeners.putIfAbsent(channel.id, holder) != null) {
            logger.warn("Stream connection for channel ${channel.id} was started concurrently, ignoring this request")
            return
        }
        tracker.markConnecting(channel.id)
        logger.info("Starting DingTalk Stream connection for channel: ${channel.id}")

        val listener = OpenDingTalkCallbackListener<ChatbotMessage, Any> { message ->
            handleBotMessage(holder, message)
            emptyMap<String, Any>()
        }

        val client = try {
            OpenDingTalkStreamClientBuilder.custom()
                .credential(AuthClientCredential(clientId, clientSecret))
                .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, listener)
                .build()
        } catch (e: Exception) {
            listeners.remove(channel.id, holder)
            tracker.markFailed(channel.id, e.message ?: "client build failed")
            logger.error("Failed to build DingTalk Stream client for channel: ${channel.id}", e)
            throw ChannelSendException(
                channelType = ChannelType.DINGTALK,
                platformErrorCode = null,
                message = "Failed to start DingTalk Stream: ${e.message}",
                cause = e,
            )
        }
        holder.client = client

        // client.start() is synchronous and performs blocking network I/O (endpoint
        // lookup + WebSocket handshake), so it never runs on the caller's thread: the
        // reconcile loop must not stall behind a unreachable gateway.
        val thread = Thread({ runConnection(holder, client) }, "dingtalk-stream-channel-${channel.id}")
        thread.isDaemon = true
        holder.thread = thread
        thread.start()
    }

    private fun runConnection(
        holder: Listener,
        client: OpenDingTalkClient,
    ) {
        val channelId = holder.channel.id
        if (holder.closing.get()) {
            // stop() ran while this thread was being scheduled; do not open a socket nobody owns.
            runCatching { client.stop() }
            listeners.remove(channelId, holder)
            return
        }
        try {
            client.start()
            if (holder.closing.get()) return
            // start() completing means the handshake succeeded; the SDK keeps the socket up.
            tracker.markConnected(channelId)
            logger.info("DingTalk Stream connection started for channel: $channelId")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!holder.closing.get()) tracker.markFailed(channelId, "interrupted")
        } catch (e: Throwable) {
            if (holder.closing.get()) return
            logger.error("DingTalk Stream connection failed for channel: $channelId", e)
            tracker.markFailed(channelId, e.message ?: "stream client failure")
        } finally {
            listeners.remove(channelId, holder)
            deduplicators.remove(channelId)
        }
    }

    override fun stop(channel: ChannelSpec) {
        val holder = listeners.remove(channel.id)
        deduplicators.remove(channel.id)
        sessionWebhooks.remove(channel.id)
        proactiveTargets.remove(channel.id)
        if (holder == null) {
            tracker.markStopped(channel.id)
            logger.warn("No DingTalk Stream connection found for channel: ${channel.id}")
            return
        }
        holder.closing.set(true)
        try {
            holder.client?.stop()
            logger.info("DingTalk Stream connection stopped for channel: ${channel.id}")
        } catch (e: Exception) {
            logger.error("Failed to stop DingTalk Stream for channel: ${channel.id}", e)
        }
        val thread = holder.thread
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_MS) }
            if (thread.isAlive) {
                logger.warn("DingTalk Stream thread for channel ${channel.id} did not exit within ${STOP_JOIN_MS}ms")
            }
        }
        tracker.markStopped(channel.id)
    }

    /** Stop every listener (application shutdown). */
    fun shutdown() {
        listeners.values.forEach { holder ->
            runCatching { stop(holder.channel) }
        }
        listeners.clear()
        deduplicators.clear()
        sessionWebhooks.clear()
        proactiveTargets.clear()
        tokenCache.clear()
    }

    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        val started = System.currentTimeMillis()
        var error: Throwable? = null
        try {
            val webhook = resolveWebhook(channel.id, sessionId)
            val chunks = TextChunker.splitByChars(message, MAX_MESSAGE_CHUNK_CHARS)
            if (chunks.size > 1) {
                logger.info("Splitting long message into {} chunks for session={}", chunks.size, sessionId)
            }
            if (webhook != null) {
                for (chunk in chunks) {
                    handleWebhookResponse(httpClient.postJson(webhook, DingtalkMessageBuilder.buildText(chunk)))
                }
            } else {
                sendProactiveText(channel, sessionId, chunks)
            }
        } catch (e: Exception) {
            error = e
            throw e
        } finally {
            metricsSink.onSendCompleted(channel.id, System.currentTimeMillis() - started, error)
        }
    }

    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        val started = System.currentTimeMillis()
        var error: Throwable? = null
        try {
            val webhook = resolveWebhook(channel.id, sessionId)
            if (webhook != null) {
                handleWebhookResponse(httpClient.postJson(webhook, DingtalkMessageBuilder.buildFromRichMessage(richMessage)))
            } else {
                val text = when (richMessage) {
                    is com.agnetix.harnax.channel.sdk.message.TextRichMessage -> richMessage.content
                    is com.agnetix.harnax.channel.sdk.message.MarkdownRichMessage -> richMessage.content
                    else -> richMessage.toString()
                }
                sendProactiveText(channel, sessionId, TextChunker.splitByChars(text, MAX_MESSAGE_CHUNK_CHARS))
            }
        } catch (e: Exception) {
            error = e
            throw e
        } finally {
            metricsSink.onSendCompleted(channel.id, System.currentTimeMillis() - started, error)
        }
    }

    /**
     * Fallback sending via the DingTalk robot OpenAPI, used when the temporary
     * sessionWebhook of the conversation has expired or was never observed.
     */
    private suspend fun sendProactiveText(
        channel: ChannelSpec,
        sessionId: String,
        chunks: List<String>,
    ) {
        val target = proactiveTargets[channel.id]?.get(sessionId)
        val appId = channel.appId
        val appSecret = channel.appSecret
        if (target == null || appId.isNullOrBlank() || appSecret.isNullOrBlank()) {
            throw ChannelSendException(
                channelType = ChannelType.DINGTALK,
                platformErrorCode = null,
                message = "No usable sessionWebhook and no cached conversation for $sessionId; " +
                    "the user must send a new message first, or the channel is missing appId/appSecret",
            )
        }
        val token = getAccessToken(appId, appSecret)
        val msgParam = objectMapper.writeValueAsString(mapOf("content" to chunks.joinToString("\n")))
        val headers = mapOf("x-acs-dingtalk-access-token" to token)
        if (target.isGroup) {
            val body = mapOf(
                "robotCode" to target.robotCode,
                "openConversationId" to target.conversationId,
                "msgKey" to "sampleText",
                "msgParam" to msgParam,
            )
            handleApiResponse(httpClient.postJson("$API_BASE/v1.0/robot/groupMessages/send", body, headers))
        } else {
            val userId = target.userId
            if (userId.isNullOrBlank()) {
                throw ChannelSendException(
                    channelType = ChannelType.DINGTALK,
                    platformErrorCode = null,
                    message = "Cannot send proactively to conversation $sessionId: no sender userId cached",
                )
            }
            val body = mapOf(
                "robotCode" to target.robotCode,
                "userIds" to listOf(userId),
                "msgKey" to "sampleText",
                "msgParam" to msgParam,
            )
            handleApiResponse(httpClient.postJson("$API_BASE/v1.0/robot/oToMessages/batchSend", body, headers))
        }
        logger.info("DingTalk message sent via robot OpenAPI for session={}", sessionId)
    }

    // ==================== Internal ====================

    private fun handleBotMessage(
        holder: Listener,
        message: ChatbotMessage,
    ) {
        val channelId = holder.channel.id
        if (holder.closing.get() || listeners[channelId] !== holder) {
            logger.debug("Ignoring DingTalk message from a superseded listener, channel: $channelId")
            return
        }
        tracker.markConnected(channelId)

        val msgKey = message.msgId?.takeIf { it.isNotBlank() }
        val dedup = msgKey?.let { deduplicators.computeIfAbsent(channelId) { MessageDeduplicator() } }
        if (msgKey != null && dedup != null && !dedup.tryBegin(msgKey)) {
            logger.debug("Duplicate DingTalk message ignored: msgId=$msgKey, channel=$channelId")
            tracker.onDuplicateMessage(channelId)
            return
        }
        val rollback = { if (msgKey != null) dedup?.rollback(msgKey) }
        val commit = { if (msgKey != null) dedup?.commit(msgKey) }

        val channelMessage = parseChatbotMessage(holder.channel, message)
        if (channelMessage == null) {
            rollback()
            return
        }
        cacheConversation(channelId, channelMessage.sessionId, holder.channel, message)

        val handler = holder.messageHandler
        if (handler == null) {
            logger.warn("No message handler registered for channel: $channelId")
            rollback()
            return
        }

        tracker.markMessageReceived(channelId)
        turnExecutor.launchTurn(channelId, channelMessage.sessionId) {
            try {
                handler(channelMessage)
                commit()
            } catch (e: Exception) {
                logger.error("Failed to handle DingTalk message for channel: $channelId", e)
                rollback()
            }
        }
    }

    private fun parseChatbotMessage(channel: ChannelSpec, message: ChatbotMessage): ChannelMessage? {
        val text = message.text?.content?.trim()
        if (text.isNullOrEmpty()) {
            logger.debug("Ignoring non-text DingTalk message (msgtype={}) for channel={}", message.msgtype, channel.id)
            return null
        }
        // conversationType: "1" = 1:1, "2" = group
        val isGroup = message.conversationType == "2"
        val sessionId = message.conversationId ?: message.senderStaffId ?: return null

        return ChannelMessage.builder()
            .messageId(message.msgId)
            .sessionId(sessionId)
            .messageType(MessageType.TEXT)
            .content(text)
            .channelType(ChannelType.DINGTALK)
            .senderName(message.senderNick)
            .senderId(message.senderStaffId)
            .isGroupMessage(isGroup)
            .groupId(if (isGroup) message.conversationId else null)
            .rawContent(message)
            .build()
    }

    /**
     * Cache everything needed to reach this conversation later: the short-lived reply
     * webhook, and the conversation coordinates for the OpenAPI fallback.
     */
    private fun cacheConversation(
        channelId: Long,
        sessionId: String,
        channel: ChannelSpec,
        message: ChatbotMessage,
    ) {
        val now = System.currentTimeMillis()
        message.sessionWebhook?.let { webhook ->
            val expireAt = message.sessionWebhookExpiredTime ?: (now + 90 * 60 * 1000)
            val submap = sessionWebhooks.computeIfAbsent(channelId) { ConcurrentHashMap() }
            // Evict already-expired entries so stale conversations don't accumulate.
            submap.entries.removeIf { it.value.expireAt <= now }
            submap[sessionId] = WebhookEntry(webhook, expireAt)
        }
        val robotCode = channel.appId
        if (!robotCode.isNullOrBlank() && !message.conversationId.isNullOrBlank()) {
            val targets = proactiveTargets.computeIfAbsent(channelId) { ConcurrentHashMap() }
            if (targets.size >= MAX_PROACTIVE_TARGETS) targets.clear()
            targets[sessionId] = ProactiveTarget(
                robotCode = robotCode,
                isGroup = message.conversationType == "2",
                conversationId = message.conversationId!!,
                userId = message.senderStaffId,
            )
        }
    }

    private fun resolveWebhook(channelId: Long, sessionId: String): String? {
        val entry = sessionWebhooks[channelId]?.get(sessionId) ?: return null
        if (entry.expireAt <= System.currentTimeMillis()) {
            logger.warn("sessionWebhook expired for channel={}, session={}, falling back to robot OpenAPI", channelId, sessionId)
            return null
        }
        return entry.url
    }

    private suspend fun getAccessToken(
        appKey: String,
        appSecret: String,
    ): String {
        val now = System.currentTimeMillis()
        tokenCache[appKey]?.let { if (it.expiresAt > now) return it.token }

        val response = httpClient.postJson(
            "$API_BASE/v1.0/oauth2/accessToken",
            mapOf("appKey" to appKey, "appSecret" to appSecret),
        )
        return when (response) {
            is PlatformResponse.Success -> {
                val json = parseJson(response.body)
                val token = json.path("accessToken").asText("")
                if (token.isBlank()) {
                    throw ChannelSendException(
                        channelType = ChannelType.DINGTALK,
                        platformErrorCode = json.path("code").asText(null),
                        message = "Failed to get DingTalk access token: ${response.body.take(200)}",
                    )
                }
                val ttlSec = json.path("expireIn").asLong(7200L)
                tokenCache[appKey] = CachedToken(token, now + ((ttlSec - TOKEN_REFRESH_SAFETY_SEC).coerceAtLeast(1L)) * 1000)
                token
            }
            is PlatformResponse.Error -> throw ChannelSendException(
                channelType = ChannelType.DINGTALK,
                platformErrorCode = response.statusCode.toString(),
                message = "Failed to get DingTalk access token: ${response.body.take(200)}",
                cause = response.exception,
            )
        }
    }

    private fun handleWebhookResponse(response: PlatformResponse) {
        when (response) {
            is PlatformResponse.Success -> {
                // DingTalk webhook returns {"errcode":0,"errmsg":"ok"} on success.
                val errcode = parseJson(response.body).path("errcode").asInt(0)
                if (errcode != 0) {
                    throw ChannelSendException(
                        channelType = ChannelType.DINGTALK,
                        platformErrorCode = errcode.toString(),
                        message = "DingTalk send failed: ${response.body.take(500)}",
                    )
                }
                logger.info("DingTalk message sent successfully")
            }
            is PlatformResponse.Error -> throw ChannelSendException(
                channelType = ChannelType.DINGTALK,
                platformErrorCode = response.statusCode.toString(),
                message = "DingTalk send failed: ${response.body.take(500)}",
                cause = response.exception,
            )
        }
    }

    /** OpenAPI (v1.0) responses carry `code`/`message` only when they failed. */
    private fun handleApiResponse(response: PlatformResponse) {
        when (response) {
            is PlatformResponse.Success -> {
                val json = parseJson(response.body)
                val code = json.path("code").asText("")
                if (code.isNotBlank()) {
                    throw ChannelSendException(
                        channelType = ChannelType.DINGTALK,
                        platformErrorCode = code,
                        message = "DingTalk OpenAPI send failed: ${json.path("message").asText("")}",
                    )
                }
                logger.info("DingTalk message sent via OpenAPI")
            }
            is PlatformResponse.Error -> {
                val json = parseJson(response.body)
                throw ChannelSendException(
                    channelType = ChannelType.DINGTALK,
                    platformErrorCode = json.path("code").asText(response.statusCode.toString()),
                    message = "DingTalk OpenAPI send failed: ${json.path("message").asText(response.body.take(200))}",
                    cause = response.exception,
                )
            }
        }
    }

    private fun parseJson(body: String?): JsonNode {
        if (body.isNullOrBlank()) return objectMapper.createObjectNode()
        return try {
            objectMapper.readTree(body)
        } catch (e: Exception) {
            logger.warn("DingTalk response is not valid JSON: ${body.take(200)}")
            objectMapper.createObjectNode()
        }
    }
}
