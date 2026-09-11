package com.agnetix.harnax.channel.service.monitor

import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionState
import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionStatus
import com.agnetix.harnax.channel.sdk.util.ErrorText
import com.agnetix.harnax.channel.service.manager.ChannelAdaptorRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for "what should be running, and what actually is".
 *
 * Before this class existed, the only observable fact was "startChannel was called once".
 * A WebSocket that died silently thirty minutes later looked exactly like a healthy one,
 * so an operator could not tell a working deployment from one where every channel was gone.
 *
 * [ChannelAdaptor.connectionStates] reports the live transport state; this component joins it
 * with the set of channels the bootstrap loop believes should be listening, and answers
 * the two questions the reconcile loop, the health indicator and the monitor endpoint ask:
 *
 * - is this channel actually serving traffic right now?
 * - which channels are declared but not serving?
 */
@Component
class ChannelRuntimeMonitor(
    private val adaptorRegistry: ChannelAdaptorRegistry,
) {

    private val expected = ConcurrentHashMap<Long, ExpectedChannel>()

    /**
     * States the transports cannot report themselves: a channel whose start threw before the
     * socket existed has no tracker entry at all, so without this overlay it would look merely
     * "not started yet" instead of FAILED.
     */
    private val startupFailures = ConcurrentHashMap<Long, ChannelConnectionState>()

    /**
     * A channel the bootstrap loop started (or intends to keep running) with an active listener.
     */
    data class ExpectedChannel(
        val id: Long,
        val name: String,
        val type: String,
        val communicationMode: String,
    ) {
        companion object {
            fun from(spec: ChannelSpec) = ExpectedChannel(
                id = spec.id,
                name = spec.name,
                type = spec.type.code,
                communicationMode = spec.communicationMode,
            )
        }
    }

    /**
     * One channel as seen by the operator: declared config merged with live transport state.
     */
    data class ChannelRuntimeView(
        val channelId: Long,
        val name: String,
        val type: String,
        val communicationMode: String,
        val status: ChannelConnectionStatus,
        /** True when the transport is connected, connecting or reconnecting. */
        val serving: Boolean,
        val statusAgeMs: Long,
        val lastConnectedAgoMs: Long,
        val lastActivityAgoMs: Long,
        val reconnectCount: Long,
        val receivedCount: Long,
        val lastError: String?,
        /** Declared in DB as an active listener but the transport knows nothing about it. */
        val missingListener: Boolean,
    )

    fun trackExpected(spec: ChannelSpec) {
        expected[spec.id] = ExpectedChannel.from(spec)
    }

    /**
     * Register an expected channel from raw entity fields, for the case where the config cannot
     * even be converted to a [ChannelSpec] — the channel still has to show up in the health view.
     */
    fun trackExpected(
        channelId: Long,
        name: String,
        type: String,
        communicationMode: String,
    ) {
        expected[channelId] = ExpectedChannel(channelId, name, type, communicationMode)
    }

    fun untrackExpected(channelId: Long) {
        expected.remove(channelId)
        startupFailures.remove(channelId)
    }

    /**
     * Records that a channel could not even be started (bad credentials, unparsable config).
     * Kept until the next successful start or until the channel is untracked.
     */
    fun markStartupFailed(
        channelId: Long,
        error: String,
    ) {
        startupFailures[channelId] = ChannelConnectionState(
            channelId = channelId,
            status = ChannelConnectionStatus.FAILED,
            sinceMillis = System.currentTimeMillis(),
            lastError = ErrorText.sanitize(error),
        )
    }

    fun clearStartupFailure(channelId: Long) {
        startupFailures.remove(channelId)
    }

    /** Live state of one channel, queried from every adaptor; UNKNOWN when none reports it. */
    fun stateOf(channelId: Long): ChannelConnectionState {
        adaptorRegistry.all().forEach { adaptor ->
            val state = adaptor.connectionState(channelId)
            if (state.status != ChannelConnectionStatus.UNKNOWN) return state
        }
        return startupFailures[channelId] ?: ChannelConnectionState(channelId)
    }

    /**
     * Declared channels merged with live transport state, ordered by id for a stable diff.
     */
    fun snapshot(now: Long = System.currentTimeMillis()): List<ChannelRuntimeView> = expected.values.sortedBy { it.id }.map { channel ->
        val state = stateOf(channel.id)
        ChannelRuntimeView(
            channelId = channel.id,
            name = channel.name,
            type = channel.type,
            communicationMode = channel.communicationMode,
            status = state.status,
            serving = state.isServing(),
            statusAgeMs = state.statusAgeMs(now),
            lastConnectedAgoMs = ageOf(state.lastConnectedAt, now),
            lastActivityAgoMs = ageOf(state.lastActivityAt, now),
            reconnectCount = state.reconnectCount,
            receivedCount = state.receivedCount,
            lastError = state.lastError,
            missingListener = state.status == ChannelConnectionStatus.UNKNOWN,
        )
    }

    fun statusCounts(): Map<ChannelConnectionStatus, Int> = snapshot().groupingBy { it.status }.eachCount()

    /**
     * Compact one-line summary, logged by the reconcile loop so "nothing happened" and
     * "everything is dead" are distinguishable in the log alone.
     */
    fun summaryLine(): String {
        val counts = statusCounts()
        if (counts.isEmpty()) return "channels=none"
        return counts.entries.sortedBy { it.key.name }
            .joinToString(prefix = "channels=${counts.values.sum()} ", separator = ", ") { "${it.key}=${it.value}" }
    }

    private fun ageOf(millis: Long, now: Long): Long = if (millis <= 0) -1L else (now - millis).coerceAtLeast(0)
}
