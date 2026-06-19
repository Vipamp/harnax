package com.agnetix.harnax.channel.sdk.adaptor

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.ChannelRequest
import com.agnetix.harnax.channel.sdk.message.RichMessage

/**
 * Channel Adapter Interface
 * Defines the common behavior contract for all channels
 *
 * The SDK version uses ChannelRequest instead of HttpServletRequest,
 * decoupling from the Servlet framework to allow SDK usage in non-Servlet environments.
 *
 * Lifecycle:
 * 1. receiveMessage  - Receive and validate message
 * 2. parseMessage    - Parse message into unified format
 * 3. (AgentAdaptor)  - Agent processes the message
 * 4. buildResponse   - Build response for Agent reply
 * 5. sendMessage     - Push message to platform
 */
interface ChannelAdaptor {

    /**
     * Get platform type
     */
    fun getType(): ChannelType

    /**
     * Verify callback signature
     * @param request Platform-agnostic request object
     * @param channel Channel configuration
     * @return Whether the signature is valid
     */
    fun verifySignature(request: ChannelRequest, channel: ChannelSpec): Boolean

    /**
     * Parse message
     * @param request Platform-agnostic request object
     * @return Unified message object
     */
    fun parseMessage(request: ChannelRequest): ChannelMessage

    /**
     * Build response
     * @param reply AI reply content
     * @param originalMessage Original message
     * @return Platform-specific response format
     */
    fun buildResponse(reply: String, originalMessage: ChannelMessage): Any

    /**
     * Push message to platform
     * @param channel Channel configuration
     * @param sessionId Session identifier
     * @param message Message content
     */
    suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String)

    /**
     * Send rich message to platform
     * @param channel Channel configuration
     * @param sessionId Session identifier
     * @param richMessage Rich message object
     */
    suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage) {
        // Default implementation: degrade rich message to plain text
        val textContent = when (richMessage) {
            is com.agnetix.harnax.channel.sdk.message.TextRichMessage -> richMessage.content
            is com.agnetix.harnax.channel.sdk.message.MarkdownRichMessage -> richMessage.content
            else -> richMessage.toString()
        }
        sendMessage(channel, sessionId, textContent)
    }

    /**
     * Handle URL verification request (verification on initial configuration)
     * @param request Platform-agnostic request object
     * @param channel Channel configuration
     * @return Verification response, null indicates non-verification request
     */
    fun handleUrlVerification(request: ChannelRequest, channel: ChannelSpec): Any? = null

    /**
     * Whether this channel supports streaming output
     *
     * Returns true if the channel can send text fragments incrementally to users,
     * enabling real-time display of AI responses as they are generated.
     *
     * Channels that support streaming:
     * - HTTP SSE: Each text fragment can be sent as a separate SSE event
     * - Some platforms that support message update/edit APIs
     *
     * Channels that don't support streaming:
     * - WeChat: Can only send complete messages via ILinkClient
     * - Feishu: API doesn't support partial message updates
     * - DingTalk: Similar limitations
     *
     * @return Whether streaming output is supported
     */
    fun supportsStreamingOutput(): Boolean = false

    /**
     * Determine whether to use streaming output for the given agent.
     *
     * This is the single decision point for stream vs batch output strategy.
     * Each channel implementation can override this to control its own behavior.
     *
     * Default implementation requires BOTH:
     * - Channel supports streaming output (supportsStreamingOutput() = true)
     * - Agent supports streaming (agentAdaptor.supportsStreaming() = true)
     *
     * Override examples:
     * - A channel that simulates streaming via message edit API can return true
     *   even if supportsStreamingOutput() is false
     * - A channel that wants to always batch can return false regardless of agent
     *
     * @param agentAdaptor The agent adaptor that will process the message
     * @return Whether to use streaming output
     */
    fun shouldUseStreaming(agentAdaptor: AgentAdaptor): Boolean = supportsStreamingOutput() && agentAdaptor.supportsStreaming()

    /**
     * Send streaming text fragment
     *
     * Called by ChannelChatService for each TextStreamEvent when the channel
     * supports real-time streaming output (supportsStreamingOutput() = true).
     *
     * Only effective when supportsStreamingOutput() returns true.
     * Default implementation is no-op.
     *
     * @param channel Channel configuration
     * @param sessionId Session identifier
     * @param fragment Text fragment content
     * @param isLast Whether this is the last fragment
     */
    suspend fun sendStreamingFragment(channel: ChannelSpec, sessionId: String, fragment: String, isLast: Boolean) {}

    /**
     * Send typing indicator
     *
     * Called by ChannelChatService to show a "thinking/typing" indicator
     * to the user while the AI is processing the response.
     *
     * Useful for channels that don't support streaming output but can
     * display a typing indicator (e.g., WeChat supports "typing" status).
     *
     * @param channel Channel configuration
     * @param sessionId Session identifier
     */
    suspend fun sendTypingIndicator(channel: ChannelSpec, sessionId: String) {}
}
