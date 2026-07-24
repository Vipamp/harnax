package com.agnetix.harnax.channel.wecom

import java.time.Duration

/**
 * Exponential reconnection backoff for the WeCom WebSocket connection.
 *
 * Mirrors cc-connect behavior:
 * - starts at 1s, doubles on each failure up to a max of 30s
 * - if the previous connection was alive longer than [resetThreshold], the
 *   backoff resets to the initial value (the disconnect was not a rapid failure loop)
 *
 * This class is pure logic (no I/O) to make the reconnection policy unit-testable.
 */
class ReconnectBackoff(
    private val initial: Duration = Duration.ofSeconds(1),
    private val max: Duration = Duration.ofSeconds(30),
    private val resetThreshold: Duration = Duration.ofSeconds(60),
) {
    private var current: Duration = initial

    /** The delay to wait before the next reconnection attempt. */
    fun currentDelay(): Duration = current

    /**
     * Called after a connection ended. [connectionAlive] is how long the connection
     * stayed up. Returns the delay to wait before reconnecting.
     */
    fun onDisconnected(connectionAlive: Duration): Duration {
        if (connectionAlive > resetThreshold) {
            current = initial
            return current
        }
        val delay = current
        var next = current.multipliedBy(2)
        if (next > max) next = max
        current = next
        return delay
    }

    fun reset() {
        current = initial
    }
}
