package com.agnetix.harnax.channel.sdk.util

import java.time.Duration

/**
 * Exponential reconnection backoff.
 *
 * Behaviour:
 * - starts at [initial], doubles on each failure up to [max]
 * - if the previous connection stayed alive longer than [resetThreshold], the backoff
 *   resets to [initial] (that disconnect was not part of a rapid failure loop)
 *
 * Pure logic (no I/O), so the reconnection policy is unit-testable and can be shared by
 * both the per-channel transports and the service-level reconcile loop.
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
