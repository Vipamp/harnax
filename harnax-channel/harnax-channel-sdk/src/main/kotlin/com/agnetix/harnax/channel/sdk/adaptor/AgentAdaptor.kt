package com.agnetix.harnax.channel.sdk.adaptor

import com.agnetix.harnax.agent.protocol.AgentRequest
import com.agnetix.harnax.agent.protocol.FileAttachment
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.message.AgentMessage
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.RichMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Agent Message Processor Abstract Class
 *
 * As the core abstraction of the SDK, it defines the lifecycle of message processing
 * from channel reception to completion:
 * 1. Channel receives message → ChannelAdaptor parses it into ChannelMessage
 * 2. AgentAdaptor.process()/streamProcess() handles ChannelMessage, produces response
 * 3. ChannelAdaptor sends response back through the original channel
 *
 * Two processing modes:
 * - process(): Returns a single AgentResponse (batch mode, suitable for simple agents)
 * - streamProcess(): Returns Flow<AgentStreamEvent> (streaming mode, suitable for AI agents with real-time output)
 *
 * Default streamProcess() implementation wraps process() result into a single-element Flow,
 * so subclasses only implementing process() will still work with streaming infrastructure.
 *
 * Design Principles:
 * - Platform-agnostic: AgentAdaptor doesn't care which channel the message comes from (WeChat/Feishu/DingTalk/HTTP)
 * - Extensible: Provides complete context information through AgentContext
 * - Observable: Offers beforeProcess/afterProcess/onError hooks
 * - Fault-tolerant: Provides default onError implementation that subclasses can override
 *
 * Usage:
 * ```
 * // Batch mode (simple agent)
 * class MyAgentAdaptor : AgentAdaptor() {
 *     override fun getName() = "my-agent"
 *
 *     override suspend fun process(context: AgentContext): AgentResponse {
 *         val reply = callLLM(context.message.content, context.history)
 *         return AgentResponse(content = reply)
 *     }
 * }
 *
 * // Streaming mode (AI agent with real-time output)
 * class MyStreamingAgentAdaptor : AgentAdaptor() {
 *     override fun getName() = "my-streaming-agent"
 *     override fun supportsStreaming() = true
 *
 *     override fun streamProcess(context: AgentContext): Flow<AgentStreamEvent> {
 *         return callLLMStream(context.message.content, context.history)
 *             .map { AgentStreamEvent.TextStreamEvent(it, false) }
 *             .concatWith(flow { emit(AgentStreamEvent.EndStreamEvent()) })
 *     }
 * }
 * ```
 */
abstract class AgentAdaptor {

    /**
     * Get Agent name/identifier
     * Used for logging and monitoring
     */
    abstract fun getName(): String

    /**
     * Process message - core processing logic
     *
     * Receives messages from the channel, processes them through the Agent, and returns a response.
     * This is the core method of the entire message processing flow and must be implemented by subclasses.
     *
     * @param context Message processing context, containing original message, history, channel config, etc.
     * @return Agent processing result
     */
    abstract suspend fun process(context: AgentContext): AgentResponse

    /**
     * Streaming process message - returns a Flow of stream events
     *
     * This method enables real-time output from AI agents. Each AgentStreamEvent represents
     * a fragment of the AI response (text, thinking, completion, or error).
     *
     * Default implementation wraps the process() result into a single-element Flow:
     * - Emits TextStreamEvent with the complete response content
     * - Emits EndStreamEvent to signal completion
     *
     * Subclasses that support streaming should override this method to emit text fragments
     * incrementally, providing a better user experience for channels that support real-time output.
     *
     * @param context Message processing context
     * @return Flow of AgentStreamEvent events
     */
    open fun streamProcess(context: AgentContext): Flow<AgentStreamEvent> = flow {
        val response = process(context)
        emit(AgentStreamEvent.TextStreamEvent(response.content, true))
        emit(AgentStreamEvent.EndStreamEvent(fullContent = response.content))
    }

    /**
     * Whether this AgentAdaptor supports streaming output
     *
     * Returns true if streamProcess() emits incremental TextStreamEvent fragments
     * (i.e., not just wrapping process() result as a single event).
     *
     * ChannelChatService uses this flag together with ChannelAdaptor.supportsStreamingOutput()
     * to decide the output strategy:
     * - Both true → Send each text fragment immediately (real-time streaming)
     * - Either false → Buffer all text and send as single message (batch mode)
     *
     * @return Whether streaming output is supported
     */
    open fun supportsStreaming(): Boolean = false

    /**
     * Determine whether to support processing this message
     *
     * Can filter based on message type, channel type, etc.
     * Returns false to skip the message without calling process().
     *
     * @param message Channel message
     * @return Whether to support processing
     */
    open fun supports(message: ChannelMessage): Boolean = true

    /**
     * Pre-processing hook
     *
     * Called before process(), can be used for:
     * - Pre-processing message content
     * - Logging request information
     * - Rate limit checks
     * - Context enhancement
     *
     * @param context Message processing context
     */
    open suspend fun onBeforeProcess(context: AgentContext) {}

    /**
     * Post-processing hook
     *
     * Called after process() completes successfully, can be used for:
     * - Logging response information
     * - Metrics collection
     * - Message persistence
     *
     * @param context Message processing context
     * @param response Agent processing result
     */
    open suspend fun onAfterProcess(context: AgentContext, response: AgentResponse) {}

    /**
     * Error handling hook
     *
     * Called when process() throws an exception, can be used for:
     * - Degraded response
     * - Error reporting
     * - Retry logic
     *
     * Default implementation returns an error message.
     *
     * @param context Message processing context
     * @param error Exception object
     * @return Degraded Agent response
     */
    open suspend fun onError(context: AgentContext, error: Throwable): AgentResponse {
        val requestId = context.requestId
        val errorDetail = error.message ?: "unknown error"
        val content = if (requestId.isNotEmpty()) {
            "[SYSTEM_ERROR][$requestId][处理过程中发生异常: $errorDetail]"
        } else {
            "[SYSTEM_ERROR][处理过程中发生异常: $errorDetail]"
        }
        return AgentResponse(
            content = content,
            shouldReply = true,
            metadata = mapOf("error" to errorDetail),
        )
    }
}

/**
 * Agent Message Processing Context
 *
 * Encapsulates all information required by AgentAdaptor.process(),
 * including original message, session history, channel configuration, and custom metadata.
 */
data class AgentContext(
    /**
     * Original channel message
     */
    val message: ChannelMessage,

    /**
     * Session history messages
     */
    val history: List<AgentMessage> = emptyList(),

    /**
     * Channel configuration information
     */
    val channelSpec: ChannelSpec,

    /**
     * Parsed AgentRequest from the channel message.
     * May be ChatAgentRequest or CommandAgentRequest depending on the message content.
     * Set by ChannelChatService after MessageParser processes the ChannelMessage.
     */
    val agentRequest: AgentRequest? = null,

    /**
     * Request identifier for error tracing/debugging.
     * Generated by ChannelChatService at the start of each chat() call.
     */
    val requestId: String = "",

    /**
     * Custom metadata
     * Can be used to pass request-level additional information (e.g., traceId, userId, etc.)
     */
    val metadata: Map<String, Any> = emptyMap(),
)

/**
 * Agent Processing Result
 *
 * Return type of AgentAdaptor.process(),
 * Contains the reply content and optional rich message and metadata.
 */
data class AgentResponse(
    /**
     * Reply text content
     */
    val content: String,

    /**
     * Rich message object (optional)
     * If set, the channel adaptor should prioritize sending the rich message
     */
    val richMessage: RichMessage? = null,

    /**
     * Whether to reply
     * In some scenarios, the Agent may not need to reply (e.g., bypass processing that only logs)
     */
    val shouldReply: Boolean = true,

    /**
     * Custom metadata
     * Can be used to pass additional processing result information (e.g., token usage, model name, etc.)
     */
    val metadata: Map<String, Any> = emptyMap(),

    /**
     * File attachments produced during agent execution.
     * Channel adaptors should download and send these files via platform API.
     */
    val attachments: List<FileAttachment> = emptyList(),
)
