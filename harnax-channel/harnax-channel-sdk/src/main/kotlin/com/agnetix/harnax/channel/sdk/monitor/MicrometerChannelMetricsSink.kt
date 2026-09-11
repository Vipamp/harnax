package com.agnetix.harnax.channel.sdk.monitor

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Built-in [ChannelMetricsSink] that reports to Micrometer, so a host application gets the whole
 * metric surface by registering one bean instead of writing the sink itself.
 *
 * micrometer-core is a `provided` dependency of this module: any Spring Boot actuator application
 * already has it on the classpath, and a host that never references this class does not need it
 * at runtime.
 *
 * Exposes what an operator needs to see about a set of long-lived platform connections:
 *
 * - `channel.connection.up{channel,type}` — 1 while the transport is serving, 0 otherwise.
 *   This is the series an alert should key on: a channel that is configured, started and
 *   silently dead is otherwise indistinguishable from a healthy idle one.
 * - `channel.connection.transitions{from,to}` — how often connections flap.
 * - `channel.messages.received` / `channel.messages.duplicate` — a rising duplicate rate is the
 *   fingerprint of the platform retrying because our listener was too slow to ack.
 * - `channel.turn.duration` / `channel.send.duration` — a downstream slowdown shows up as latency
 *   here before it shows up as lost messages.
 *
 * Timers and gauges are cached per channel: building them on every message would put a tag-hash
 * and a concurrent lookup on the hot path.
 */
class MicrometerChannelMetricsSink(
    private val registry: MeterRegistry,
) : ChannelMetricsSink {

    private val log = LoggerFactory.getLogger(MicrometerChannelMetricsSink::class.java)

    private val turnTimers = ConcurrentHashMap<String, Timer>()

    private val sendTimers = ConcurrentHashMap<String, Timer>()

    /** One holder per channel; the gauge reads it, so no registration churn on state changes. */
    private val connectionFlags = ConcurrentHashMap<Long, AtomicInteger>()

    override fun onConnectionStateChanged(
        state: ChannelConnectionState,
        previous: ChannelConnectionStatus,
    ) {
        flagOf(state.channelId).set(if (state.isServing()) 1 else 0)
        registry
            .counter(
                COUNTER_TRANSITIONS,
                TAG_FROM,
                previous.name,
                TAG_TO,
                state.status.name,
                TAG_CHANNEL,
                state.channelId.toString(),
            ).increment()
        log.info(
            "[Metrics] channel={} connection {} -> {} (reconnects={}, lastError={})",
            state.channelId,
            previous,
            state.status,
            state.reconnectCount,
            state.lastError ?: "-",
        )
    }

    override fun onMessageReceived(
        channelId: Long,
        channelType: String,
    ) {
        registry
            .counter(COUNTER_MESSAGES, TAG_CHANNEL, channelId.toString(), TAG_TYPE, channelType)
            .increment()
    }

    override fun onDuplicateMessage(
        channelId: Long,
        channelType: String,
    ) {
        registry
            .counter(COUNTER_DUPLICATES, TAG_CHANNEL, channelId.toString(), TAG_TYPE, channelType)
            .increment()
    }

    override fun onTurnCompleted(
        channelId: Long,
        elapsedMs: Long,
        error: Throwable?,
    ) {
        record(turnTimers, TIMER_TURN, channelId, elapsedMs)
        countTurns(channelId, error)
    }

    override fun onSendCompleted(
        channelId: Long,
        elapsedMs: Long,
        error: Throwable?,
    ) {
        record(sendTimers, TIMER_SEND, channelId, elapsedMs)
        countSends(channelId, error)
    }

    /**
     * Forget a channel that has been deleted or disabled: the series disappears instead of
     * sitting at 0 forever, so `channel.connection.up == 0` always means "still configured,
     * currently down" and never a channel nobody manages any more.
     */
    override fun onChannelRemoved(channelId: Long) {
        val key = channelId.toString()
        turnTimers.remove(key)
        sendTimers.remove(key)
        connectionFlags.remove(channelId)?.let { holder ->
            holder.set(0)
            registry
                .find(GAUGE_CONNECTION_UP)
                .tag(TAG_CHANNEL, key)
                .meter()
                ?.let { registry.remove(it) }
        }
    }

    private fun flagOf(channelId: Long): AtomicInteger = connectionFlags.computeIfAbsent(channelId) { id ->
        AtomicInteger(0).also { value ->
            registry.gauge(
                GAUGE_CONNECTION_UP,
                listOf(Tag.of(TAG_CHANNEL, id.toString())),
                value,
            ) { holder: AtomicInteger -> holder.get().toDouble() }
        }
    }

    private fun record(
        cache: ConcurrentHashMap<String, Timer>,
        name: String,
        channelId: Long,
        elapsedMs: Long,
    ) {
        val key = channelId.toString()
        cache
            .computeIfAbsent(key) { id ->
                Timer
                    .builder(name)
                    .tag(TAG_CHANNEL, id)
                    .publishPercentiles(P50, P95, P99)
                    .register(registry)
            }.record(elapsedMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    private fun countTurns(
        channelId: Long,
        error: Throwable?,
    ) {
        registry
            .counter(COUNTER_TURNS, TAG_CHANNEL, channelId.toString(), TAG_OUTCOME, outcome(error))
            .increment()
    }

    private fun countSends(
        channelId: Long,
        error: Throwable?,
    ) {
        registry
            .counter(COUNTER_SENDS, TAG_CHANNEL, channelId.toString(), TAG_OUTCOME, outcome(error))
            .increment()
    }

    private fun outcome(error: Throwable?): String = if (error == null) "success" else "error"

    private companion object {
        const val TAG_CHANNEL = "channel"
        const val TAG_TYPE = "type"
        const val TAG_FROM = "from"
        const val TAG_TO = "to"
        const val TAG_OUTCOME = "outcome"

        const val GAUGE_CONNECTION_UP = "channel.connection.up"
        const val COUNTER_TRANSITIONS = "channel.connection.transitions"
        const val COUNTER_MESSAGES = "channel.messages.received"
        const val COUNTER_DUPLICATES = "channel.messages.duplicate"
        const val COUNTER_TURNS = "channel.turn"
        const val COUNTER_SENDS = "channel.send"
        const val TIMER_TURN = "channel.turn.duration"
        const val TIMER_SEND = "channel.send.duration"

        const val P50 = 0.5
        const val P95 = 0.95
        const val P99 = 0.99
    }
}
