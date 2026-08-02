package com.agnetix.harnax.channel.sdk.service

import com.agnetix.harnax.agent.protocol.AgentRequest
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
     * The caller (e.g. ChannelManager) can use this to persist the pending confirm state.
     */
    protected val onPendingConfirm: ((sessionId: String, tools: List<PendingToolInfo>) -> Unit)? = null,
    /**
     * Optional resolver for downloading file content from internal storage (e.g. MinIO).
     * If null, falls back to HTTP download from attachment URL.
     */
    protected val fileContentResolver: FileContentResolver? = null,
    /**
     * Optional downloader for fetching files directly from sandbox workspace.
     * Used for channel sessions where files are not uploaded to MinIO.
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
            } else {
                // Send fallback notification to user instead of silent no-op
                val fallbackMsg = "\u26a0\ufe0f AI processing completed but returned no content. Please try again."
                logger.warn("[Batch] Empty response for session=${message.sessionId}, sending fallback message")
                try {
                    channelAdaptor.sendMessage(channel, message.sessionId, fallbackMsg)
                } catch (e: Exception) {
                    logger.error("[Batch] Failed to send fallback message for session=${message.sessionId}: ${e.message}", e)
                }
            }

            // Save AI reply to session
            saveAssistantMessage(message, responseText, channel)
            // Call post-processing hook
            agentAdaptor.onAfterProcess(context, response)
        }

        // Deliver file attachments regardless of shouldReply
        // (agent may generate files without text reply)
        if (response.attachments.isNotEmpty()) {
            deliverFileAttachments(channel, context.channelSpec.sessionId, message.sessionId, response.attachments, channelAdaptor)
        }
    }

    /**
     * Deliver file attachments to the channel user.
     * Downloads file bytes from workspace/MinIO and sends via channelAdaptor.sendFile().
     *
     * @param agentSessionId Channel session ID (chn-xxx) for workspace download
     * @param userSessionId  Platform user ID for sending the file message
     */
    protected open suspend fun deliverFileAttachments(
        channel: ChannelSpec,
        agentSessionId: String,
        userSessionId: String,
        attachments: List<com.agnetix.harnax.agent.protocol.FileAttachment>,
        channelAdaptor: ChannelAdaptor,
    ) {
        for (attachment in attachments) {
            try {
                val fileBytes = resolveFileBytes(agentSessionId, attachment)
                    ?: throw IllegalStateException("Unable to resolve file content for '${attachment.fileName}'")
                channelAdaptor.sendFile(
                    channel = channel,
                    sessionId = userSessionId,
                    fileBytes = fileBytes,
                    fileName = attachment.fileName,
                    caption = "AI 生成的文件",
                )
                logger.info("[Batch] File '{}' ({} bytes) delivered to user={}", attachment.fileName, fileBytes.size, userSessionId)
            } catch (e: Exception) {
                logger.error("[Batch] Failed to deliver file '{}' for user={}: {}", attachment.fileName, userSessionId, e.message)
                // Degrade: send text notification
                try {
                    channelAdaptor.sendMessage(channel, userSessionId, "\uD83D\uDCCE 文件 ${attachment.fileName} 发送失败，请通过 WebUI 下载")
                } catch (e2: Exception) {
                    logger.error("[Batch] Failed to send file failure notification: {}", e2.message)
                }
            }
        }
    }

    /**
     * Resolve file bytes using the best available strategy:
     * 1. Workspace download (channel sessions: filePath present, objectKey empty)
     * 2. MinIO resolver (web/task sessions: objectKey present)
     * 3. HTTP URL fallback
     *
     * Returns null if all strategies fail or return empty content.
     */
    private fun resolveFileBytes(sessionId: String, attachment: com.agnetix.harnax.agent.protocol.FileAttachment): ByteArray? {
        // Strategy 1: Direct workspace download (no MinIO round-trip)
        if (attachment.filePath.isNotBlank() && attachment.objectKey.isBlank()) {
            val bytes = workspaceFileDownloader?.invoke(sessionId, attachment.filePath)
            if (bytes != null && bytes.isNotEmpty()) {
                logger.info("[Batch] File '{}' resolved via workspace download ({} bytes)", attachment.fileName, bytes.size)
                return bytes
            }
            logger.warn("[Batch] Workspace download failed for '{}', trying fallbacks", attachment.fileName)
        }

        // Strategy 2: MinIO internal resolver
        val resolved = fileContentResolver?.resolve(attachment)
        if (resolved != null && resolved.isNotEmpty()) return resolved

        // Strategy 3: HTTP URL fallback
        if (attachment.url.isNotBlank()) {
            val url = java.net.URL(attachment.url)
            require(url.protocol == "http" || url.protocol == "https") { "Unsafe URL scheme: ${url.protocol}" }
            val downloaded = url.readBytes()
            if (downloaded.isNotEmpty()) return downloaded
        }

        return null
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
        if (errorResponse.shouldReply) {
            channelAdaptor.sendMessage(channel, message.sessionId, errorResponse.content)
            saveAssistantMessage(message, errorResponse.content, channel)
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
     * Format: `[error-code][request-id][user-readable message]`
     * - error-code: from HarnaxErrorCode (e.g., "6001")
     * - request-id: short request identifier for tracing (e.g., "req-a1b2c3d4")
     * - message: user-readable error description
     */
    private fun formatErrorMessage(code: String, requestId: String, message: String): String = "[$code][$requestId][$message]"

    /**
     * Build a plain-text confirmation message from pending tool info.
     * Used for channels that cannot render rich UI (e.g. Feishu, WeChat).
     */
    protected fun buildConfirmText(tools: List<PendingToolInfo>): String {
        val sb = StringBuilder()
        sb.appendLine("\u26a0\ufe0f AI needs to execute the following tools, please confirm:")
        sb.appendLine()
        tools.forEachIndexed { index, tool ->
            val dangerTag = if (tool.isDangerous) " [dangerous]" else ""
            val argsSummary = tool.arguments.entries.joinToString(", ") { "${it.key}: ${it.value}" }
            sb.appendLine("${index + 1}. ${tool.toolName}$dangerTag \u2014 $argsSummary")
        }
        sb.appendLine()
        sb.appendLine("Reply /approve to confirm, or /deny to reject.")
        return sb.toString().trimEnd()
    }
}
