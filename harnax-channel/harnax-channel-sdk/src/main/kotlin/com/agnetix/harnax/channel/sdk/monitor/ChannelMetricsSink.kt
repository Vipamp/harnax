package com.agnetix.harnax.channel.sdk.monitor

/**
 * Hook for exporting channel runtime events to a metrics backend.
 *
 * Kept deliberately small and free of any metrics-library type, so a host can implement it
 * without taking a dependency. [MicrometerChannelMetricsSink] is provided for hosts that already
 * run Micrometer; everything else falls back to [NoOpChannelMetricsSink].
 */
interface ChannelMetricsSink {
    /** A listener moved to a new connection state. */
    fun onConnectionStateChanged(state: ChannelConnectionState, previous: ChannelConnectionStatus) {}

    /** A message was accepted from the platform (after dedup). */
    fun onMessageReceived(channelId: Long, channelType: String) {}

    /** A duplicate message was filtered out. */
    fun onDuplicateMessage(channelId: Long, channelType: String) {}

    /**
     * One agent turn finished (successfully or not).
     * [error] is null when the turn completed normally.
     */
    fun onTurnCompleted(channelId: Long, elapsedMs: Long, error: Throwable?) {}

    /** An outbound send to the platform finished. */
    fun onSendCompleted(channelId: Long, elapsedMs: Long, error: Throwable?) {}

    /**
     * The channel was deleted or disabled and will not serve again.
     * Implementations should drop any per-channel series, otherwise a gauge left at 0 keeps
     * reporting a connection that no longer exists.
     */
    fun onChannelRemoved(channelId: Long) {}
}

/** Default sink used when no metrics backend is wired in (SDK-only usage, demos, tests). */
object NoOpChannelMetricsSink : ChannelMetricsSink
