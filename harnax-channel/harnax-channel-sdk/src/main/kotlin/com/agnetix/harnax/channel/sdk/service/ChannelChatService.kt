package com.agnetix.harnax.channel.sdk.service

import com.agnetix.harnax.agent.protocol.AgentRequest
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.protocol.FileAttachment
import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.AgentContext
import com.agnetix.harnax.channel.sdk.adaptor.AgentResponse
import com.agnetix.harnax.channel.sdk.adaptor.AgentStreamEvent
import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.PendingToolInfo
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageRole
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import kotlinx.coroutines.flow.collect
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.UUID

/**
 * Channel Chat Service
 *
 * Core orchestration service that connects ChannelAdaptor (external channels)
 * and AgentAdaptor (AI Agent), providing a unified message processing pipeline.
 *
 * Responsibilities:
 * 1. Receive user messages from channels
 * 2. Save messages to session history
 * 3. Build AgentContext with conversation history
 * 4. Call AgentAdaptor to process messages
 * 5. Determine output strategy based on channel and agent capabilities:
 *    - Streaming: Send text fragments immediately (for channels supporting real-time output)
 *    - Batch: Buffer all text, send typing indicator, then send merged response
 * 6. Save AI replies to session history
 * 7. Deliver any files the run produced, through the one entry point both output strategies share
 *
 * Output Strategy Decision:
 * ```
 * supportsStreamingOutput() && supportsStreaming() → streamAndSend()
 * else → batchSend()
 * ```
 *
 * This class only depends on SDK abstractions, no external framework dependencies.
 * Uses Kotlin Flow for streaming (not Reactor Flux) to keep SDK lightweight.
 */
open class ChannelChatService(
    protected val sessionManager: ChannelSessionManager,
    /**
     * Optional callback invoked when a HITL tool confirmation event is received.
     * The caller can use this to persist the pending confirm state for the session.
     */
    protected val onPendingConfirm: ((sessionId: String, tools: List<PendingToolInfo>) -> Unit)? = null,
    /**
     * Optional downloader for fetching files directly from sandbox workspace.
     * Channel sessions download files via workspace API (no MinIO round-trip).
     * Parameters: (sessionId, filePath) -> file bytes or null.
     */
    protected val workspaceFileDownloader: ((sessionId: String, filePath: String) -> ByteArray?)? = null,
) {

    private val logger = LoggerFactory.getLogger(ChannelChatService::class.java)

    /**
     * Process channel message - core entry method
     *
     * Complete flow:
     * 1. Read conversation history (before saving current message to avoid duplication)
     * 2. Save user message to session
     * 3. Build AgentContext with history
     * 4. Determine output strategy (streaming vs batch)
     * 5. Call AgentAdaptor and send response via ChannelAdaptor
     * 6. Save AI reply to session
     *
     * @param message User message from channel
     * @param channel Channel configuration
     * @param agentAdaptor AI Agent processor
     * @param channelAdaptor Channel adapter for sending messages
     */
    suspend fun chat(
        message: ChannelMessage,
        channel: ChannelSpec,
        agentAdaptor: AgentAdaptor,
        channelAdaptor: ChannelAdaptor,
        agentRequest: AgentRequest? = null,
    ) {
        // Generate short request ID for error tracing: req-xxxxxxxx
        val requestId = "req-${UUID.randomUUID().toString().take(8)}"

        logger.info("[Chat] Received message for session=${message.sessionId}, channel=${channel.id}, content='${message.content.take(50)}', requestId=$requestId")

        try {
            // 1. Read history BEFORE saving current message to avoid duplication
            val history = sessionManager.getHistory(channel.id, message.sessionId)
            val agentMessages = sessionManager.toAgentMessages(history)

            // 2. Save user message to session
            sessionManager.addMessage(channel.id, message)
            val context = AgentContext(
                message = message,
                history = agentMessages,
                channelSpec = channel,
                agentRequest = agentRequest,
                requestId = requestId,
            )

            // 3. Call pre-processing hook
            agentAdaptor.onBeforeProcess(context)

            // 4. Determine output strategy - delegated to channel adaptor
            if (channelAdaptor.shouldUseStreaming(agentAdaptor)) {
                streamAndSend(context, channel, message, channelAdaptor, agentAdaptor)
            } else {
                batchSend(context, channel, message, channelAdaptor, agentAdaptor)
            }

            // 5. /clear resets the agent's own context, but the channel keeps a parallel history
            // and replays it into every request. Left in place, the "cleared" conversation would be
            // handed straight back on the next message.
            if (agentRequest is CommandAgentRequest && agentRequest.command == CommandType.CLEAR) {
                sessionManager.clearHistory(channel.id, message.sessionId)
                logger.info("[Chat] Cleared channel-side history for session=${message.sessionId} after /clear")
            }
        } catch (e: Exception) {
            logger.error("[$requestId] Error processing message from channel ${channel.id}: ${e.message}", e)
            handleChatError(e, message, channel, agentAdaptor, channelAdaptor, requestId)
        }
    }

    /**
     * Streaming output strategy
     *
     * For channels that support real-time streaming output:
     * - Each TextStreamEvent is sent immediately via sendStreamingFragment()
     * - ThinkingStreamEvent triggers sendTypingIndicator()
     * - EndStreamEvent completes the stream
     *
     * The channel can display AI output incrementally to the user.
     */
    protected suspend fun streamAndSend(
        context: AgentContext,
        channel: ChannelSpec,
        message: ChannelMessage,
        channelAdaptor: ChannelAdaptor,
        agentAdaptor: AgentAdaptor,
    ) {
        val fullContent = StringBuilder()

        agentAdaptor.streamProcess(context).collect { event ->
            when (event) {
                is AgentStreamEvent.TextStreamEvent -> {
                    fullContent.append(event.content)
                    channelAdaptor.sendStreamingFragment(channel, message.sessionId, event.content, event.isLast)
                }
                is AgentStreamEvent.ThinkingStreamEvent -> {
                    channelAdaptor.sendTypingIndicator(channel, message.sessionId)
                    logger.debug("AI thinking: ${event.content.take(50)}...")
                }
                is AgentStreamEvent.EndStreamEvent -> {
                    logger.info("Streaming output completed for session ${message.sessionId}")
                    // Save AI reply to session
                    saveAssistantMessage(message, fullContent.toString(), channel)
                    // Call post-processing hook
                    val response = AgentResponse(
                        content = fullContent.toString(),
                        shouldReply = true,
                    )
                    agentAdaptor.onAfterProcess(context, response)
                    // Same delivery entry point the batch path uses, so a streaming channel is not
                    // the one that loses the agent's files.
                    deliverFileAttachments(channel, channel.sessionId, message.sessionId, event.attachments, channelAdaptor)
                }
                is AgentStreamEvent.ErrorStreamEvent -> {
                    logger.error("[${event.code}][${event.requestId}] Stream error for session ${message.sessionId}: ${event.message}", event.cause)
                    channelAdaptor.sendMessage(
                        channel,
                        message.sessionId,
                        formatErrorMessage(event.code, event.requestId, event.message),
                    )
                }
                is AgentStreamEvent.ToolConfirmStreamEvent -> {
                    // HITL: convert pending tools to plain-text confirmation message
                    val confirmText = buildConfirmText(event.pendingTools)
                    channelAdaptor.sendMessage(channel, message.sessionId, confirmText)
                    saveAssistantMessage(message, confirmText, channel)
                    // Notify caller to persist pending confirm state
                    onPendingConfirm?.invoke(message.sessionId, event.pendingTools)
                    logger.info("Tool confirm event sent for session ${message.sessionId}, tools=${event.pendingTools.size}")
                }
            }
        }
    }

    /**
     * Batch output strategy
     *
     * Calls process() directly (non-streaming endpoint) to get the complete response,
     * then sends it as a single message via sendMessage().
     *
     * This is simpler and more efficient than streaming + buffering:
     * - Uses standard HTTP request instead of SSE connection
     * - No need to maintain a long-lived connection while waiting
     * - Sends typing indicator while AI is processing
     */
    protected suspend fun batchSend(
        context: AgentContext,
        channel: ChannelSpec,
        message: ChannelMessage,
        channelAdaptor: ChannelAdaptor,
        agentAdaptor: AgentAdaptor,
    ) {
        // Send typing indicator to show AI is processing
        channelAdaptor.sendTypingIndicator(channel, message.sessionId)

        logger.info("[Batch] Calling process() for session=${message.sessionId}, channel=${channel.id}")

        val startTime = System.currentTimeMillis()
        val response = agentAdaptor.process(context)
        val elapsed = System.currentTimeMillis() - startTime
        val responseText = response.content

        logger.info("[Batch] process() returned for session=${message.sessionId}, shouldReply=${response.shouldReply}, contentLength=${responseText.length}, elapsed=${elapsed}ms")

        if (response.shouldReply) {
            if (responseText.isNotBlank()) {
                channelAdaptor.sendMessage(channel, message.sessionId, responseText)
                logger.info("[Batch] Response sent for session=${message.sessionId}, text length=${responseText.length}")
            } else if (response.attachments.isEmpty()) {
                // Send fallback notification to user instead of silent no-op
                val fallbackMsg = "${ReplyMarkers.EMPTY_REPLY_PREFIX}. Please try again."
                logger.warn("[Batch] Empty response for session=${message.sessionId}, sending fallback message")
                try {
                    channelAdaptor.sendMessage(channel, message.sessionId, fallbackMsg)
                } catch (e: Exception) {
                    logger.error("[Batch] Failed to send fallback message for session=${message.sessionId}: ${e.message}", e)
                }
            } else {
                // The file notice below already answers "what happened", so a second message
                // claiming the agent returned nothing would only contradict it.
                logger.info("[Batch] Empty text for session=${message.sessionId}, ${response.attachments.size} file(s) carry the reply")
            }

            // Save AI reply to session
            saveAssistantMessage(message, responseText, channel)
            // Call post-processing hook
            agentAdaptor.onAfterProcess(context, response)
        }

        // Files are delivered whether or not there was a text reply: an agent can produce output
        // without saying anything about it.
        deliverFileAttachments(channel, channel.sessionId, message.sessionId, response.attachments, channelAdaptor)
    }

    /**
     * Deliver the files an agent run produced.
     *
     * This is the one delivery entry point, reached from both output strategies — `batchSend`
     * passes the attachments on the batch response, `streamAndSend` the ones on the terminal
     * stream event.
     *
     * A channel without the file capability is told once, naming every file: pulling bytes for an
     * upload nobody can perform costs bandwidth and can hold the turn hostage behind a large file.
     *
     * @param agentSessionId Channel session ID (chn-xxx) for workspace download
     * @param userSessionId  Platform user ID for sending the file message
     */
    protected open suspend fun deliverFileAttachments(
        channel: ChannelSpec,
        agentSessionId: String,
        userSessionId: String,
        attachments: List<FileAttachment>,
        channelAdaptor: ChannelAdaptor,
    ) {
        if (attachments.isEmpty()) return

        if (!channelAdaptor.supportsFileDelivery()) {
            val names = attachments.map { it.fileName }
            logger.info("[Files] Channel ${channel.type.code} cannot receive files, notifying user=$userSessionId about $names")
            notifyQuietly(channel, userSessionId, ReplyMarkers.fileUndeliverable(names), channelAdaptor)
            return
        }

        for (attachment in attachments) {
            try {
                val fileBytes = resolveFileBytes(agentSessionId, attachment)
                    ?: throw IllegalStateException("Unable to resolve file content for '${attachment.fileName}'")
                channelAdaptor.sendFile(
                    channel = channel,
                    sessionId = userSessionId,
                    fileBytes = fileBytes,
                    fileName = attachment.fileName,
                    caption = FILE_CAPTION,
                )
                logger.info("[Files] '${attachment.fileName}' ({} bytes) delivered to user={}", fileBytes.size, userSessionId)
            } catch (e: Exception) {
                logger.error("[Files] Failed to deliver '{}' for user={}: {}", attachment.fileName, userSessionId, e.message)
                notifyQuietly(channel, userSessionId, ReplyMarkers.fileSendFailed(attachment.fileName), channelAdaptor)
            }
        }
    }

    /** A notice about delivery is never allowed to be what breaks the turn. */
    private suspend fun notifyQuietly(
        channel: ChannelSpec,
        userSessionId: String,
        notice: String,
        channelAdaptor: ChannelAdaptor,
    ) {
        try {
            channelAdaptor.sendMessage(channel, userSessionId, notice)
        } catch (e: Exception) {
            logger.error("[Files] Failed to notify user=$userSessionId: ${e.message}", e)
        }
    }

    /**
     * Resolve file bytes using the best available strategy:
     * 1. Workspace download (channel sessions: filePath present, objectKey empty)
     * 2. HTTP URL fallback
     *
     * Returns null if all strategies fail or return empty content.
     */
    private fun resolveFileBytes(
        sessionId: String,
        attachment: FileAttachment,
    ): ByteArray? {
        // Strategy 1: Direct workspace download (no MinIO round-trip)
        if (attachment.filePath.isNotBlank() && attachment.objectKey.isBlank()) {
            val bytes = workspaceFileDownloader?.invoke(sessionId, attachment.filePath)
            if (bytes != null && bytes.isNotEmpty()) {
                logger.info("[Files] '{}' resolved via workspace download ({} bytes)", attachment.fileName, bytes.size)
                return bytes
            }
            logger.warn("[Files] Workspace download failed for '{}', trying fallbacks", attachment.fileName)
        }

        // Strategy 2: HTTP URL fallback
        if (attachment.url.isNotBlank()) {
            val downloaded = downloadFromUrl(attachment.url)
            if (downloaded != null && downloaded.isNotEmpty()) return downloaded
        }

        return null
    }

    /**
     * Fetch an attachment over HTTP, bounded on every axis.
     *
     * This runs inside the channel turn, so without explicit timeouts an unresponsive host would
     * hold the per-session lock for as long as the JVM default allows, and without a size cap a
     * multi-gigabyte object would be pulled into heap. Redirects are refused rather than followed:
     * following one would re-validate nothing and would let a benign-looking URL reach a forbidden
     * host. The scheme and address checks stop a URL produced by a compromised agent from making
     * this service read its own host (metadata endpoints, loopback admin ports).
     *
     * An internal MinIO host is deliberately still allowed — deployments keep the object store on
     * private network.
     */
    private fun downloadFromUrl(rawUrl: String): ByteArray? {
        val url = try {
            URI.create(rawUrl).toURL()
        } catch (e: Exception) {
            logger.warn("[Files] Malformed attachment URL, skipped: ${e.message}")
            return null
        }
        if (url.protocol != "http" && url.protocol != "https") {
            logger.warn("[Files] Rejected attachment URL with unsafe scheme: ${url.protocol}")
            return null
        }
        val host = url.host
        if (host.isNullOrBlank() || !isSafeHost(host)) {
            logger.warn("[Files] Rejected attachment URL pointing at a local address: $host")
            return null
        }
        return try {
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connect()
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                logger.warn("[Files] Attachment download returned HTTP {} for host={}", status, host)
                connection.disconnect()
                return null
            }
            connection.inputStream.use { input -> readCapped(input, host) }.also { connection.disconnect() }
        } catch (e: Exception) {
            logger.warn("[Files] Attachment download failed for host={}: {}", host, e.message)
            null
        }
    }

    /** Reads at most [MAX_DOWNLOAD_BYTES]; a larger body is treated as a failure, not truncated. */
    private fun readCapped(
        input: java.io.InputStream,
        host: String,
    ): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_DOWNLOAD_BYTES) {
                logger.warn("[Files] Attachment from host={} exceeds the {} byte limit, skipped", host, MAX_DOWNLOAD_BYTES)
                return null
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun isSafeHost(host: String): Boolean = try {
        InetAddress.getAllByName(host).any { address ->
            !address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isLinkLocalAddress
        }
    } catch (e: Exception) {
        logger.warn("[Files] Unable to resolve attachment host={}: {}", host, e.message)
        false
    }

    /**
     * Handle chat error gracefully
     *
     * Uses AgentAdaptor.onError() to produce a degraded response,
     * then sends it through the channel.
     */
    protected suspend fun handleChatError(
        error: Throwable,
        message: ChannelMessage,
        channel: ChannelSpec,
        agentAdaptor: AgentAdaptor,
        channelAdaptor: ChannelAdaptor,
        requestId: String = "",
    ) {
        val context = AgentContext(
            message = message,
            channelSpec = channel,
            requestId = requestId,
        )
        val errorResponse = agentAdaptor.onError(context, error)
        if (!errorResponse.shouldReply) {
            return
        }
        // The notice must not throw. The failure it is reporting is very often the send itself
        // (an expired DingTalk sessionWebhook, a closed WeCom stream), so an unguarded retry here
        // replaces "the user got no answer" with "the turn failed", and the log then points at this
        // second call instead of at the real cause.
        try {
            channelAdaptor.sendMessage(channel, message.sessionId, errorResponse.content)
            saveAssistantMessage(message, errorResponse.content, channel)
        } catch (e: Exception) {
            logger.error(
                "[$requestId] Unable to deliver the error notice to session=${message.sessionId} (channel ${channel.id}): ${e.message}",
                e,
            )
        }
    }

    /**
     * Save assistant (AI) message to session history
     */
    protected suspend fun saveAssistantMessage(
        originalMessage: ChannelMessage,
        replyContent: String,
        channel: ChannelSpec,
    ) {
        if (replyContent.isBlank() || ReplyMarkers.isSyntheticReply(replyContent)) {
            logger.debug("[History] Skip empty/synthetic reply for session {}: {}", originalMessage.sessionId, replyContent.take(80))
            return
        }
        val assistantMessage = ChannelMessage.builder()
            .sessionId(originalMessage.sessionId)
            .role(MessageRole.ASSISTANT)
            .content(replyContent)
            .channelType(originalMessage.channelType)
            .timestamp(System.currentTimeMillis())
            .build()
        sessionManager.addMessage(channel.id, assistantMessage)
    }

    /**
     * Format error message for user-facing output.
     *
     * Format: `⚠️ AI processing failed: message (code: error-code, requestId: request-id)`
     * - error-code: from HarnaxErrorCode (e.g., "6001")
     * - request-id: short request identifier for tracing (e.g., "req-a1b2c3d4")
     * - message: user-readable error description
     */
    private fun formatErrorMessage(code: String, requestId: String, message: String): String = "${ReplyMarkers.FAILED_PREFIX}: $message (code: $code, requestId: $requestId)"

    /**
     * Build a plain-text confirmation message from pending tool info.
     * Used for channels that cannot render rich UI (e.g. Feishu, WeChat).
     */
    protected fun buildConfirmText(tools: List<PendingToolInfo>): String {
        val sb = StringBuilder()
        sb.appendLine(ReplyMarkers.CONFIRM_HEADER)
        sb.appendLine()
        tools.forEachIndexed { index, tool ->
            val dangerTag = if (tool.isDangerous) " [dangerous]" else ""
            val argsSummary = tool.arguments.entries.joinToString(", ") { "${it.key}: ${it.value}" }
            sb.appendLine("${index + 1}. ${tool.toolName}$dangerTag \u2014 $argsSummary")
        }
        sb.appendLine()
        sb.appendLine(ReplyMarkers.CONFIRM_FOOTER)
        return sb.toString().trimEnd()
    }

    companion object {
        /** Caption attached to an uploaded file; shown by the platform next to it. */
        private const val FILE_CAPTION = "AI-generated file"

        private const val MAX_DOWNLOAD_BYTES = 50L * 1024 * 1024

        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 5_000

        private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
    }
}
