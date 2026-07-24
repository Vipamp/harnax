package com.agnetix.harnax.channel.dingtalk

import com.agnetix.harnax.channel.dingtalk.client.PlatformHttpClient
import com.agnetix.harnax.channel.dingtalk.client.PlatformResponse
import com.agnetix.harnax.channel.sdk.adaptor.ChannelCommunicationMode
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.error.ChannelSendException
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageType
import com.agnetix.harnax.channel.sdk.message.RichMessage
import com.dingtalk.open.app.api.OpenDingTalkClient
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener
import com.dingtalk.open.app.api.models.bot.ChatbotMessage
import com.dingtalk.open.app.api.security.AuthClientCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

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
 */
class DingtalkStreamMode(
    private val httpClient: PlatformHttpClient = PlatformHttpClient(),
) : ChannelCommunicationMode {

    private val logger = LoggerFactory.getLogger(DingtalkStreamMode::class.java)

    private val clients = ConcurrentHashMap<Long, OpenDingTalkClient>()
    private val messageHandlers = ConcurrentHashMap<Long, suspend (ChannelMessage) -> Unit>()
    private val processedMessageIds = ConcurrentHashMap<Long, MutableSet<String>>()

    /** conversationId -> latest sessionWebhook, per channel. */
    private val sessionWebhooks = ConcurrentHashMap<Long, ConcurrentHashMap<String, WebhookEntry>>()

    /**
     * Per-channel coroutine scope for running the (potentially long) agent turn
     * off the SDK callback thread. Blocking the callback thread would delay the
     * ACK and serialize message processing, risking redelivery.
     */
    private val handlerScopes = ConcurrentHashMap<Long, CoroutineScope>()

    private data class WebhookEntry(val url: String, val expireAt: Long)

    companion object {
        private const val MAX_PROCESSED_IDS = 1000

        // DingTalk single message content limit is 20000 chars; keep headroom for markdown wrapper.
        private const val MAX_MESSAGE_CHUNK_CHARS = 18_000
    }

    override fun getModeName(): String = "stream"

    override fun isCallbackMode(): Boolean = false

    override fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit) {
        if (clients.containsKey(channel.id)) {
            logger.warn("Stream connection already exists for channel: ${channel.id}")
            return
        }

        val clientId = channel.appId
        val clientSecret = channel.appSecret
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            throw IllegalArgumentException("Stream mode requires appId(clientId) and appSecret(clientSecret) for channel: ${channel.id}")
        }

        logger.info("Starting DingTalk Stream connection for channel: ${channel.id}")

        messageHandlers[channel.id] = messageHandler
        handlerScopes[channel.id] = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val listener = OpenDingTalkCallbackListener<ChatbotMessage, Any> { message ->
            handleBotMessage(channel, message)
            emptyMap<String, Any>()
        }

        try {
            val client = OpenDingTalkStreamClientBuilder.custom()
                .credential(AuthClientCredential(clientId, clientSecret))
                .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, listener)
                .build()

            // Register the client before starting so stop() can close it even
            // while the connection is still being established on the background thread.
            clients[channel.id] = client

            // client.start() is synchronous and performs blocking network I/O
            // (endpoint lookup + WebSocket handshake). Run it on a background
            // daemon thread so a slow/unreachable gateway never stalls the
            // reconcile loop (which holds reconcileLock). Mirrors FeishuWebSocketMode.
            Thread {
                try {
                    client.start()
                    logger.info("DingTalk Stream connection started for channel: ${channel.id}")
                } catch (e: Exception) {
                    logger.error("DingTalk Stream connection failed for channel: ${channel.id}", e)
                    clients.remove(channel.id)
                    messageHandlers.remove(channel.id)
                    processedMessageIds.remove(channel.id)
                    handlerScopes.remove(channel.id)?.cancel()
                }
            }.apply {
                isDaemon = true
                name = "dingtalk-stream-channel-${channel.id}"
                start()
            }
        } catch (e: Exception) {
            logger.error("Failed to build DingTalk Stream client for channel: ${channel.id}", e)
            clients.remove(channel.id)
            messageHandlers.remove(channel.id)
            processedMessageIds.remove(channel.id)
            handlerScopes.remove(channel.id)?.cancel()
            throw ChannelSendException(
                channelType = ChannelType.DINGTALK,
                platformErrorCode = null,
                message = "Failed to start DingTalk Stream: ${e.message}",
                cause = e,
            )
        }
    }

    override fun stop(channel: ChannelSpec) {
        val client = clients.remove(channel.id)
        messageHandlers.remove(channel.id)
        processedMessageIds.remove(channel.id)
        sessionWebhooks.remove(channel.id)
        handlerScopes.remove(channel.id)?.cancel()

        if (client != null) {
            try {
                client.stop()
                logger.info("DingTalk Stream connection stopped for channel: ${channel.id}")
            } catch (e: Exception) {
                logger.error("Failed to stop DingTalk Stream for channel: ${channel.id}", e)
            }
        } else {
            logger.warn("No DingTalk Stream connection found for channel: ${channel.id}")
        }
    }

    override suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String) {
        val webhook = resolveWebhook(channel.id, sessionId)
            ?: throw ChannelSendException(
                channelType = ChannelType.DINGTALK,
                platformErrorCode = null,
                message = "No valid sessionWebhook for conversation $sessionId; the user must send a new message first",
            )

        val chunks = splitMessageIntoChunks(message, MAX_MESSAGE_CHUNK_CHARS)
        if (chunks.size > 1) {
            logger.info("Splitting long message into {} chunks for session={}", chunks.size, sessionId)
        }
        for (chunk in chunks) {
            val body = DingtalkMessageBuilder.buildText(chunk)
            handleSendResponse(httpClient.postJson(webhook, body))
        }
    }

    override suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        val webhook = resolveWebhook(channel.id, sessionId)
            ?: throw ChannelSendException(
                channelType = ChannelType.DINGTALK,
                platformErrorCode = null,
                message = "No valid sessionWebhook for conversation $sessionId; the user must send a new message first",
            )
        val body = DingtalkMessageBuilder.buildFromRichMessage(richMessage)
        handleSendResponse(httpClient.postJson(webhook, body))
    }

    // ==================== Internal ====================

    private fun handleBotMessage(channel: ChannelSpec, message: ChatbotMessage) {
        val msgId = message.msgId
        if (!msgId.isNullOrBlank() && !markMessageProcessed(channel.id, msgId)) {
            logger.debug("Duplicate DingTalk message ignored: msgId=$msgId, channel=${channel.id}")
            return
        }

        val channelMessage = parseChatbotMessage(channel, message) ?: return
        cacheWebhook(channel.id, channelMessage.sessionId, message)

        val scope = handlerScopes[channel.id]
        if (scope == null) {
            logger.warn("No handler scope for channel: ${channel.id}, message dropped")
            return
        }
        scope.launch {
            try {
                messageHandlers[channel.id]?.invoke(channelMessage)
                    ?: logger.warn("No message handler registered for channel: ${channel.id}")
            } catch (e: Exception) {
                logger.error("Failed to handle DingTalk message for channel: ${channel.id}", e)
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

    private fun cacheWebhook(channelId: Long, sessionId: String, message: ChatbotMessage) {
        val webhook = message.sessionWebhook ?: return
        val expireAt = message.sessionWebhookExpiredTime ?: (System.currentTimeMillis() + 90 * 60 * 1000)
        val submap = sessionWebhooks.computeIfAbsent(channelId) { ConcurrentHashMap() }
        // Evict already-expired entries so stale conversations don't accumulate.
        val now = System.currentTimeMillis()
        submap.entries.removeIf { it.value.expireAt <= now }
        submap[sessionId] = WebhookEntry(webhook, expireAt)
    }

    private fun resolveWebhook(channelId: Long, sessionId: String): String? {
        val entry = sessionWebhooks[channelId]?.get(sessionId) ?: return null
        if (entry.expireAt <= System.currentTimeMillis()) {
            logger.warn("sessionWebhook expired for channel={}, session={}", channelId, sessionId)
            return null
        }
        return entry.url
    }

    private fun handleSendResponse(response: PlatformResponse) {
        when (response) {
            is PlatformResponse.Success -> {
                // DingTalk webhook returns {"errcode":0,"errmsg":"ok"} on success.
                val errcode = Regex("\"errcode\"\\s*:\\s*(\\d+)").find(response.body)?.groupValues?.get(1)?.toIntOrNull()
                if (errcode != null && errcode != 0) {
                    throw ChannelSendException(
                        channelType = ChannelType.DINGTALK,
                        platformErrorCode = errcode.toString(),
                        message = "DingTalk send failed: ${response.body}",
                    )
                }
                logger.info("DingTalk message sent successfully")
            }
            is PlatformResponse.Error -> throw ChannelSendException(
                channelType = ChannelType.DINGTALK,
                platformErrorCode = response.statusCode.toString(),
                message = "DingTalk send failed: ${response.body}",
                cause = response.exception,
            )
        }
    }

    /**
     * Split a long message on newline/space boundaries to respect DingTalk's per-message limit.
     */
    private fun splitMessageIntoChunks(message: String, maxChars: Int): List<String> {
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

    /**
     * Record a processed message ID; returns false if it was already processed (duplicate).
     * Uses a bounded LRU set per channel.
     */
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
