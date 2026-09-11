package com.agnetix.harnax.channel.service.client

import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Fail-fast circuit for the session-router calls.
 *
 * Without it, every inbound message keeps paying the full router timeout while router or
 * agent-service is down: the channel thread blocks for up to `channel.proxy.response-timeout-ms`
 * (10 minutes by default), so a single outage slowly occupies every worker in
 * [com.agnetix.harnax.channel.sdk.dispatch.ChannelTurnExecutor] and healthy channels queue behind
 * doomed requests. Once the circuit is open, calls fail immediately with a message the user can
 * act on, and the worker is released to serve the channels that still work.
 *
 * States:
 * - CLOSED — normal; [failureThreshold] consecutive failures open the circuit
 * - OPEN — reject immediately until [openDuration] has passed
 * - HALF_OPEN — let one probe through; success closes, failure reopens
 *
 * Deliberately counts *consecutive* failures rather than a rate: the traffic here is spiky per
 * channel, and a sliding-window percentage over a low-traffic minute would either never trip or
 * trip on a single unlucky message.
 *
 * Hand-rolled instead of Resilience4j because this is the only circuit in the service and adding a
 * dependency (plus its scheduler) is not worth the extra moving part.
 */
class RouterCircuitBreaker(
    private val enabled: Boolean = true,
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val openDuration: Duration = DEFAULT_OPEN_DURATION,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val log = LoggerFactory.getLogger(RouterCircuitBreaker::class.java)

    private var state = State.CLOSED

    private var consecutiveFailures = 0

    private var openedAt = 0L

    /**
     * Whether this call may go to the router. False means "fail now" — the caller must not retry
     * in place, otherwise the breaker would be bypassed by the very traffic it is meant to shed.
     */
    @Synchronized
    fun tryAcquire(): Boolean {
        if (!enabled) return true
        return when (state) {
            State.CLOSED -> true
            State.OPEN, State.HALF_OPEN -> {
                if (clock() - openedAt < openDuration.toMillis()) {
                    false
                } else {
                    // Only one probe at a time; the second caller keeps failing fast while the
                    // first finds out whether the router is back.
                    if (state == State.OPEN) {
                        state = State.HALF_OPEN
                        log.warn("Router circuit HALF_OPEN: allowing one probe call after {}ms", openDuration.toMillis())
                        true
                    } else {
                        // Already probing and the probe never reported (or is still in flight):
                        // re-arm so a lost probe cannot leave the circuit stuck open forever.
                        if (clock() - openedAt >= openDuration.toMillis() * PROBE_STUCK_MULTIPLIER) {
                            log.error("Router circuit probe did not report within {}ms; allowing another call", openDuration.toMillis() * PROBE_STUCK_MULTIPLIER)
                            openedAt = clock()
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun onSuccess() {
        if (!enabled) return
        if (state != State.CLOSED) {
            log.info("Router circuit CLOSED again: router reachable after {}ms open", clock() - openedAt)
        }
        state = State.CLOSED
        consecutiveFailures = 0
        openedAt = 0L
    }

    @Synchronized
    fun onFailure(error: String?) {
        if (!enabled) return
        consecutiveFailures++
        if (state == State.HALF_OPEN) {
            open("probe failed: $error")
            return
        }
        if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
            open("$consecutiveFailures consecutive failures, last: $error")
        }
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "state" to state.name,
        "consecutiveFailures" to consecutiveFailures,
        "openedAt" to openedAt,
        "failureThreshold" to failureThreshold,
        "openDurationMs" to openDuration.toMillis(),
        "enabled" to enabled,
    )

    private fun open(reason: String) {
        state = State.OPEN
        openedAt = clock()
        log.error("Router circuit OPEN for {}ms ({}); channel calls will fail fast", openDuration.toMillis(), reason)
    }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 5
        val DEFAULT_OPEN_DURATION: Duration = Duration.ofSeconds(30)

        /** Allowances before a stuck HALF_OPEN probe is considered lost. */
        private const val PROBE_STUCK_MULTIPLIER = 2L
    }
}
