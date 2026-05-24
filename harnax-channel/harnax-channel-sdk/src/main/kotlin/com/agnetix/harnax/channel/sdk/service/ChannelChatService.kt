package com.agnetix.harnax.channel.sdk.service

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
     * 1. Save user message to session
     * 2. Build AgentContext with history
     * 3. Determine output strategy (streaming vs batch)
     * 4. Call AgentAdaptor and send response via ChannelAdaptor
     * 5. Save AI reply to session
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
    ) {
        try {
            // 1. Save user message to session
            sessionManager.addMessage(channel.id, message)

            // 2. Build context with conversation history
            val history = sessionManager.getHistory(channel.id, message.sessionId)
            val agentMessages = sessionManager.toAgentMessages(history)
            val context = AgentContext(
                message = message,
                history = agentMessages,
                channelSpec = channel,
            )

            // 3. Call pre-processing hook
            agentAdaptor.onBeforeProcess(context)

            // 4. Determine output strategy based on channel and agent capabilities
            if (channelAdaptor.supportsStreamingOutput() && agentAdaptor.supportsStreaming()) {
                streamAndSend(context, channel, message, channelAdaptor, agentAdaptor)
            } else {
                batchSend(context, channel, message, channelAdaptor, agentAdaptor)
            }
        } catch (e: Exception) {
            logger.error("Error processing message from channel ${channel.id}: ${e.message}", e)
            handleChatError(e, message, channel, agentAdaptor, channelAdaptor)
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
                    logger.error("Stream error for session ${message.sessionId}: ${event.error}", event.cause)
                    channelAdaptor.sendMessage(channel, message.sessionId, "Sorry, an error occurred during processing. Please try again later.")
                }
            }
        }
    }

    /**
     * Batch output strategy
     *
     * For channels that do NOT support real-time streaming output:
     * - Send typing indicator to show AI is processing
     * - Buffer all TextStreamEvent content
     * - On EndStreamEvent, send the merged complete response via sendMessage()
     *
     * This provides a good user experience even for non-streaming channels:
     * the user sees a "typing" indicator while waiting for the complete reply.
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

        val fullContent = StringBuilder()

        try {
            agentAdaptor.streamProcess(context).collect { event ->
                when (event) {
                    is AgentStreamEvent.TextStreamEvent -> {
                        fullContent.append(event.content)
                    }
                    is AgentStreamEvent.ThinkingStreamEvent -> {
                        // Continue showing typing indicator during thinking
                        channelAdaptor.sendTypingIndicator(channel, message.sessionId)
                        logger.debug("AI thinking: ${event.content.take(50)}...")
                    }
                    is AgentStreamEvent.EndStreamEvent -> {
                        // Send merged complete response
                        val responseText = fullContent.toString()
                        if (responseText.isNotBlank()) {
                            channelAdaptor.sendMessage(channel, message.sessionId, responseText)
                            logger.info("Batch response sent for session ${message.sessionId}")
                        }
                        // Save AI reply to session
                        saveAssistantMessage(message, responseText, channel)
                        // Call post-processing hook
                        val response = AgentResponse(
                            content = responseText,
                            shouldReply = true,
                        )
                        agentAdaptor.onAfterProcess(context, response)
                    }
                    is AgentStreamEvent.ErrorStreamEvent -> {
                        logger.error("Agent error for session ${message.sessionId}: ${event.error}", event.cause)
                        channelAdaptor.sendMessage(channel, message.sessionId, "Sorry, an error occurred during processing. Please try again later.")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Batch send failed for session ${message.sessionId}: ${e.message}", e)
            // Try to send whatever we've accumulated so far
            if (fullContent.isNotEmpty()) {
                channelAdaptor.sendMessage(channel, message.sessionId, fullContent.toString())
                saveAssistantMessage(message, fullContent.toString(), channel)
            }
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
    ) {
        val context = AgentContext(
            message = message,
            channelSpec = channel,
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
}
