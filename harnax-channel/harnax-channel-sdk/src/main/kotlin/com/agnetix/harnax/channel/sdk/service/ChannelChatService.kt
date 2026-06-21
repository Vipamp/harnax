package com.agnetix.harnax.channel.sdk.service

import com.agnetix.harnax.agent.protocol.AgentRequest
import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.AgentContext
import com.agnetix.harnax.channel.sdk.adaptor.AgentResponse
import com.agnetix.harnax.channel.sdk.adaptor.AgentStreamEvent
import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
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
                logger.warn("[Batch] Empty response, nothing sent for session=${message.sessionId}")
            }
            // Save AI reply to session
            saveAssistantMessage(message, responseText, channel)
            // Call post-processing hook
            agentAdaptor.onAfterProcess(context, response)
        }
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
}
