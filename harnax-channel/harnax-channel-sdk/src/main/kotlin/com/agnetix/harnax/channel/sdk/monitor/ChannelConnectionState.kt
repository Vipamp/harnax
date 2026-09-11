package com.agnetix.harnax.channel.sdk.monitor

/**
 * Observed lifecycle state of a single channel listener.
 *
 * The state is reported by the communication mode that owns the transport
 * (WebSocket / Stream / long polling) and consumed by:
 * - the reconcile loop, which restarts channels that silently died
 * - the actuator health indicator and the monitor controller
 */
enum class ChannelConnectionStatus {
    /** No listener has been created for this channel yet. */
    UNKNOWN,

    /** start() accepted the request; the transport is still being established. */
    CONNECTING,

    /** Transport is up and the listener can receive messages. */
    CONNECTED,

    /** Transport dropped; an automatic reconnect is scheduled. */
    RECONNECTING,

    /**
     * Listener failed in a way a reconnect cannot fix on its own
     * (invalid credentials, expired login, repeated connect failures).
     */
    FAILED,

    /** Listener was stopped on purpose. */
    STOPPED,
    ;

    /** True when the listener is (or is expected to be) serving traffic. */
    fun isServing(): Boolean = this == CONNECTED || this == CONNECTING || this == RECONNECTING
}

/**
 * Immutable snapshot of one channel's connection state.
 *
 * All timestamps are `System.currentTimeMillis()` values; 0 means "never happened".
 */
data class ChannelConnectionState(
    val channelId: Long,
    val status: ChannelConnectionStatus = ChannelConnectionStatus.UNKNOWN,
    /** When the current [status] was entered. */
    val sinceMillis: Long = 0,
    val lastConnectedAt: Long = 0,
    /** Last inbound message or heartbeat ack; used to spot half-dead connections. */
    val lastActivityAt: Long = 0,
    /**
     * Last application-level heartbeat ack. Stays 0 for transports whose keepalive is
     * managed inside the vendor SDK, which tells the reconcile loop not to judge them
     * by heartbeat staleness (an idle but healthy channel would be restarted forever).
     */
    val lastHeartbeatAt: Long = 0,
    val reconnectCount: Long = 0,
    val receivedCount: Long = 0,
    val lastError: String? = null,
) {
    fun isServing(): Boolean = status.isServing()

    /** How long the current status has been held, in milliseconds. */
    fun statusAgeMs(now: Long = System.currentTimeMillis()): Long = if (sinceMillis <= 0) 0 else (now - sinceMillis).coerceAtLeast(0)
}
