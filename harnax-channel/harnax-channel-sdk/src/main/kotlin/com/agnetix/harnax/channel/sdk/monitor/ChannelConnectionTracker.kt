package com.agnetix.harnax.channel.sdk.monitor

import com.agnetix.harnax.channel.sdk.util.ErrorText
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the live [ChannelConnectionState] of every channel a mode instance serves,
 * and forwards state transitions to a [ChannelMetricsSink].
 *
 * Each communication mode owns one tracker, so the union of all trackers (queried
 * through [com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor.connectionState])
 * is the authoritative answer to "is this channel actually connected right now".
 */
class ChannelConnectionTracker(
    private val sink: ChannelMetricsSink = NoOpChannelMetricsSink,
    private val channelType: String = "unknown",
) {

    private val states = ConcurrentHashMap<Long, ChannelConnectionState>()

    fun state(channelId: Long): ChannelConnectionState = states[channelId] ?: ChannelConnectionState(channelId)

    fun snapshot(): List<ChannelConnectionState> = states.values.sortedBy { it.channelId }

    fun remove(channelId: Long) {
        states.remove(channelId)
    }

    fun markConnecting(channelId: Long) = transition(channelId, ChannelConnectionStatus.CONNECTING)

    fun markConnected(channelId: Long) = transition(channelId, ChannelConnectionStatus.CONNECTED)

    fun markReconnecting(channelId: Long, error: String? = null) = transition(channelId, ChannelConnectionStatus.RECONNECTING, error)

    /** Terminal failure: reconnecting alone will not fix it, needs a restart or new config. */
    fun markFailed(channelId: Long, error: String) = transition(channelId, ChannelConnectionStatus.FAILED, error)

    fun markStopped(channelId: Long) = transition(channelId, ChannelConnectionStatus.STOPPED)

    /**
     * Records an application-level heartbeat ack. Only transports with their own ping/pong
     * protocol should call this; the reconcile loop uses it to detect a socket that looks
     * CONNECTED but has stopped answering.
     */
    fun markHeartbeat(channelId: Long) {
        val now = System.currentTimeMillis()
        states.compute(channelId) { id, prev ->
            (prev ?: ChannelConnectionState(id)).copy(lastHeartbeatAt = now, lastActivityAt = now)
        }
    }

    /** Count one accepted message; callers invoke this only after dedup has let it through. */
    fun markMessageReceived(channelId: Long) {
        states.compute(channelId) { id, prev ->
            val current = prev ?: ChannelConnectionState(id)
            current.copy(
                receivedCount = current.receivedCount + 1,
                lastActivityAt = System.currentTimeMillis(),
            )
        }
        sink.onMessageReceived(channelId, channelType)
    }

    fun onDuplicateMessage(channelId: Long) = sink.onDuplicateMessage(channelId, channelType)

    /**
     * Moves a channel to [status], emitting one sink event per actual change.
     * Repeated transitions to the same state are collapsed so a flapping reconnect
     * does not flood the metrics backend.
     */
    fun transition(channelId: Long, status: ChannelConnectionStatus, error: String? = null) {
        val safeError = error?.let { ErrorText.sanitize(it) }
        var previous = ChannelConnectionStatus.UNKNOWN
        var changed = false
        val next = states.compute(channelId) { id, prev ->
            val current = prev ?: ChannelConnectionState(id)
            if (current.status == status && (safeError == null || current.lastError == safeError)) {
                current
            } else {
                changed = true
                previous = current.status
                current.movedTo(status, safeError)
            }
        } ?: return
        if (changed) {
            sink.onConnectionStateChanged(next, previous)
        }
    }

    private fun ChannelConnectionState.movedTo(
        status: ChannelConnectionStatus,
        error: String?,
    ): ChannelConnectionState {
        val now = System.currentTimeMillis()
        val healthy = status == ChannelConnectionStatus.CONNECTED
        return copy(
            status = status,
            sinceMillis = now,
            lastConnectedAt = if (healthy) now else lastConnectedAt,
            lastActivityAt = if (healthy) now else lastActivityAt,
            reconnectCount = if (status == ChannelConnectionStatus.RECONNECTING) reconnectCount + 1 else reconnectCount,
            lastError = when {
                error != null -> error
                healthy || status == ChannelConnectionStatus.STOPPED -> null
                else -> lastError
            },
        )
    }
}
