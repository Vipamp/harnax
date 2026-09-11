package com.agnetix.harnax.channel.sdk.adaptor

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.RichMessage
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionState

/**
 * Channel Communication Mode Interface
 * Defines common behavior contracts for different communication methods (Webhook, WebSocket, etc.)
 *
 * Design Goals:
 * - Unify interfaces for different communication modes, making upper-level Adaptors unaware of the underlying method
 * - Support dynamic switching of communication modes (e.g., from Webhook to WebSocket)
 * - Extensible for new communication modes (e.g., message queues, gRPC, etc.)
 */
interface ChannelCommunicationMode {
    /**
     * Get communication mode name
     * @return Mode name (e.g., "webhook", "websocket")
     */
    fun getModeName(): String

    /**
     * Check if this is a callback mode
     * @return true if external HTTP callback is needed, false for active polling or long connection
     */
    fun isCallbackMode(): Boolean

    /**
     * Start communication mode
     * @param channel Channel configuration
     * @param messageHandler Message processing function
     *
     * Notes:
     * - Webhook mode: No-op, handled by external Servlet container
     * - WebSocket mode: Establish long connection and block listening
     */
    fun start(channel: ChannelSpec, messageHandler: suspend (ChannelMessage) -> Unit)

    /**
     * Stop communication mode
     * @param channel Channel configuration
     *
     * Notes:
     * - Webhook mode: No-op
     * - WebSocket mode: Close long connection
     */
    fun stop(channel: ChannelSpec)

    /**
     * Send text message
     * @param channel Channel configuration
     * @param sessionId Session ID
     * @param message Message content
     */
    suspend fun sendMessage(channel: ChannelSpec, sessionId: String, message: String)

    /**
     * Send rich message
     * @param channel Channel configuration
     * @param sessionId Session ID
     * @param richMessage Rich message object
     */
    suspend fun sendRichMessage(channel: ChannelSpec, sessionId: String, richMessage: RichMessage)

    /**
     * Whether this communication mode supports streaming output
     *
     * Returns true if the underlying communication method can send
     * text fragments incrementally to the platform.
     *
     * @return Whether streaming output is supported
     */
    fun supportsStreamingOutput(): Boolean = false

    /**
     * Send streaming text fragment
     *
     * Called for each text fragment when streaming output is supported.
     *
     * @param channel Channel configuration
     * @param sessionId Session ID
     * @param fragment Text fragment content
     * @param isLast Whether this is the last fragment
     */
    suspend fun sendStreamingFragment(channel: ChannelSpec, sessionId: String, fragment: String, isLast: Boolean) {}

    /**
     * Send typing indicator
     *
     * Shows a "thinking/typing" status to the user while AI is processing.
     *
     * @param channel Channel configuration
     * @param sessionId Session ID
     */
    suspend fun sendTypingIndicator(channel: ChannelSpec, sessionId: String) {}

    /**
     * Current connection state of one channel listener.
     *
     * Lets the reconcile loop and health endpoints distinguish "we asked for a listener"
     * from "the transport is actually up". Callback/webhook modes keep the default.
     */
    fun connectionState(channelId: Long): ChannelConnectionState = ChannelConnectionState(channelId)

    /** Snapshot of every channel this mode currently serves. */
    fun connectionStates(): List<ChannelConnectionState> = emptyList()
}
