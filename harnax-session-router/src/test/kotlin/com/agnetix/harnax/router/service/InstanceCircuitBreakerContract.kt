package com.agnetix.harnax.router.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest

/**
 * The breaker state machine that [InstanceCircuitBreaker] promises, run against every implementation.
 *
 * Local mode and cluster mode route differently if one of them grants a probe the other does not, and
 * a breaker is exactly the kind of component whose bugs only show up on the third node. So the
 * transitions are specified once here and both
 * [com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker] and
 * [com.agnetix.harnax.router.service.impl.RedisCircuitBreaker] have to pass them.
 *
 * It hands out dynamic tests instead of being a base class because the Redis implementation also needs
 * the shared Redis container its own base class owns, and a class can only extend one thing.
 */
class InstanceCircuitBreakerContract(
    private val factory: (failureThreshold: Int, openDurationMs: Long, probeLeaseMs: Long) -> InstanceCircuitBreaker,
    private val wipeState: () -> Unit = {},
) {

    fun tests(): List<DynamicTest> = CASES.map { (name, body) ->
        DynamicTest.dynamicTest(name) {
            wipeState()
            // A fresh breaker per case, so no test inherits another one's circuit.
            body(Scenario(factory(FAILURE_THRESHOLD, OPEN_DURATION_MS, PROBE_LEASE_MS)))
        }
    }

    /** One case's worth of breaker plus the clock vocabulary its steps are written in. */
    class Scenario(val breaker: InstanceCircuitBreaker) {
        val inst1 = "inst-1"
        val inst2 = "inst-2"
        val inst3 = "inst-3"
        val inst4 = "inst-4"
        val failureThreshold = FAILURE_THRESHOLD
        val openDurationMs = OPEN_DURATION_MS
        val probeLeaseMs = PROBE_LEASE_MS

        fun trip(instanceId: String) {
            repeat(FAILURE_THRESHOLD) { breaker.recordFailure(instanceId) }
        }

        fun sleep(millis: Long) {
            Thread.sleep(millis + SLEEP_MARGIN_MS)
        }

        /** Past the point where the open window has expired and a probe may be granted. */
        fun sleepPastWindow() {
            sleep(OPEN_DURATION_MS)
        }
    }
}

// Windows generous enough for a shared-store round trip, small enough to keep the suite quick.
private const val FAILURE_THRESHOLD = 3
private const val OPEN_DURATION_MS = 300L
private const val PROBE_LEASE_MS = 200L

/** Covers a scheduler hiccup plus the round trips a shared-store breaker adds. */
private const val SLEEP_MARGIN_MS = 80L

private val CASES: List<Pair<String, (InstanceCircuitBreakerContract.Scenario) -> Unit>> = listOf(
    "a fresh instance is closed and allows traffic" to { s ->
        assertFalse(s.breaker.isOpen(s.inst1))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, s.breaker.getState(s.inst1))
        assertEquals(0, s.breaker.getFailureCount(s.inst1))
        assertTrue(s.breaker.allowRequest(s.inst1))
    },
    "failures below the threshold leave the circuit closed" to { s ->
        s.breaker.recordFailure(s.inst1)
        s.breaker.recordFailure(s.inst1)

        assertFalse(s.breaker.isOpen(s.inst1))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, s.breaker.getState(s.inst1))
        assertEquals(2, s.breaker.getFailureCount(s.inst1))
        assertTrue(s.breaker.allowRequest(s.inst1))
    },
    "reaching the threshold opens the circuit" to { s ->
        s.trip(s.inst1)

        assertTrue(s.breaker.isOpen(s.inst1))
        assertEquals(InstanceCircuitBreaker.State.OPEN, s.breaker.getState(s.inst1))
        assertFalse(s.breaker.allowRequest(s.inst1))
    },
    "reading the breaker never changes its state" to { s ->
        s.trip(s.inst1)

        repeat(3) { assertTrue(s.breaker.isOpen(s.inst1), "isOpen must not heal the circuit by being called") }
        assertEquals(InstanceCircuitBreaker.State.OPEN, s.breaker.getState(s.inst1))
        assertFalse(s.breaker.allowRequest(s.inst1))
    },
    "the open window grants exactly one probe" to { s ->
        s.trip(s.inst1)
        s.sleepPastWindow()

        assertFalse(s.breaker.isOpen(s.inst1), "an expired window must allow a probe")
        assertTrue(s.breaker.allowRequest(s.inst1), "the first caller gets the probe")
        assertFalse(s.breaker.allowRequest(s.inst1), "the probe slot is held by that caller")
        assertFalse(s.breaker.allowRequest(s.inst1))
        assertEquals(InstanceCircuitBreaker.State.HALF_OPEN, s.breaker.getState(s.inst1))
    },
    "a failed probe reopens the circuit" to { s ->
        s.trip(s.inst1)
        s.sleepPastWindow()
        assertTrue(s.breaker.allowRequest(s.inst1))

        s.breaker.recordFailure(s.inst1)

        assertTrue(s.breaker.isOpen(s.inst1))
        assertFalse(s.breaker.allowRequest(s.inst1))
    },
    "a successful probe closes the circuit" to { s ->
        s.trip(s.inst1)
        s.sleepPastWindow()
        assertTrue(s.breaker.allowRequest(s.inst1))

        s.breaker.recordSuccess(s.inst1)

        assertFalse(s.breaker.isOpen(s.inst1))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, s.breaker.getState(s.inst1))
        assertEquals(0, s.breaker.getFailureCount(s.inst1))
        assertTrue(s.breaker.allowRequest(s.inst1))
    },
    "a probe whose result never arrives does not block recovery forever" to { s ->
        s.trip(s.inst1)
        s.sleepPastWindow()
        assertTrue(s.breaker.allowRequest(s.inst1))
        assertFalse(s.breaker.allowRequest(s.inst1))

        s.sleep(s.probeLeaseMs)

        assertFalse(s.breaker.isOpen(s.inst1))
        assertTrue(s.breaker.allowRequest(s.inst1), "an expired probe lease must be grantable again")
    },
    "failures while open do not extend the blackout" to { s ->
        s.trip(s.inst1)
        s.sleep(s.openDurationMs / 2)
        s.breaker.recordFailure(s.inst1)

        s.sleep(s.openDurationMs / 2)

        assertFalse(
            s.breaker.isOpen(s.inst1),
            "in-flight failures must not push the probe window out indefinitely",
        )
        assertTrue(s.breaker.allowRequest(s.inst1))
    },
    "success on an open circuit heals it" to { s ->
        // Sticky traffic keeps flowing to its bound instance, so a success is evidence even while the
        // breaker is OPEN and nobody has been granted a probe.
        s.trip(s.inst1)
        assertTrue(s.breaker.isOpen(s.inst1))

        s.breaker.recordSuccess(s.inst1)

        assertFalse(s.breaker.isOpen(s.inst1))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, s.breaker.getState(s.inst1))
    },
    "trippedInstances lists only the open ones" to { s ->
        s.trip(s.inst1)
        s.breaker.recordFailure(s.inst2)

        assertEquals(setOf(s.inst1), s.breaker.trippedInstances(listOf(s.inst1, s.inst2, s.inst3, s.inst4)))
        assertEquals(emptySet<String>(), s.breaker.trippedInstances(emptyList()))
    },
    "an expired window drops an instance out of the tripped set" to { s ->
        s.trip(s.inst1)
        s.trip(s.inst2)
        assertTrue(s.breaker.trippedInstances(listOf(s.inst1, s.inst2)).isNotEmpty())

        s.sleepPastWindow()

        assertEquals(emptySet<String>(), s.breaker.trippedInstances(listOf(s.inst1, s.inst2)))
    },
    "circuits are independent per instance" to { s ->
        s.trip(s.inst1)

        assertTrue(s.breaker.isOpen(s.inst1))
        assertFalse(s.breaker.isOpen(s.inst2))
        assertTrue(s.breaker.allowRequest(s.inst2))
    },
    "reset clears the circuit" to { s ->
        s.trip(s.inst1)

        s.breaker.reset(s.inst1)

        assertFalse(s.breaker.isOpen(s.inst1))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, s.breaker.getState(s.inst1))
        assertEquals(0, s.breaker.getFailureCount(s.inst1))
    },
    "an unknown instance reads as closed and tolerates writes" to { s ->
        assertEquals(InstanceCircuitBreaker.State.CLOSED, s.breaker.getState(s.inst4))
        assertEquals(0, s.breaker.getFailureCount(s.inst4))
        assertTrue(s.breaker.allowRequest(s.inst4))

        s.breaker.recordSuccess(s.inst4)
        s.breaker.reset(s.inst4)

        assertFalse(s.breaker.isOpen(s.inst4))
    },
)
