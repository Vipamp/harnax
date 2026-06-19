package com.agnetix.harnax.router.service

import com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InstanceCircuitBreakerTest {

    private lateinit var breaker: InstanceCircuitBreaker

    @BeforeEach
    fun setUp() {
        breaker = LocalInstanceCircuitBreaker(failureThreshold = 3, openDurationMs = 500)
    }

    @Test
    fun `initial state is CLOSED and not open`() {
        assertFalse(breaker.isOpen("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-1"))
    }

    @Test
    fun `single failure does not open circuit`() {
        breaker.recordFailure("inst-1")
        assertFalse(breaker.isOpen("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-1"))
        assertEquals(1, breaker.getFailureCount("inst-1"))
    }

    @Test
    fun `circuit opens after reaching failure threshold`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")

        assertTrue(breaker.isOpen("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.OPEN, breaker.getState("inst-1"))
    }

    @Test
    fun `success resets failure count and closes circuit`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordSuccess("inst-1")

        assertFalse(breaker.isOpen("inst-1"))
        assertEquals(0, breaker.getFailureCount("inst-1"))
    }

    @Test
    fun `open circuit transitions to half-open after timeout`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        assertTrue(breaker.isOpen("inst-1"))

        Thread.sleep(600)

        assertFalse(breaker.isOpen("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.HALF_OPEN, breaker.getState("inst-1"))
    }

    @Test
    fun `half-open transitions to open on failure`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")

        Thread.sleep(600)
        assertFalse(breaker.isOpen("inst-1"))

        breaker.recordFailure("inst-1")
        assertTrue(breaker.isOpen("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.OPEN, breaker.getState("inst-1"))
    }

    @Test
    fun `half-open transitions to closed on success`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")

        Thread.sleep(600)
        assertFalse(breaker.isOpen("inst-1"))

        breaker.recordSuccess("inst-1")
        assertFalse(breaker.isOpen("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-1"))
        assertEquals(0, breaker.getFailureCount("inst-1"))
    }

    @Test
    fun `different instances have independent circuit state`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")

        assertTrue(breaker.isOpen("inst-1"))
        assertFalse(breaker.isOpen("inst-2"))
    }

    @Test
    fun `reset clears circuit state for instance`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        assertTrue(breaker.isOpen("inst-1"))

        breaker.reset("inst-1")
        assertFalse(breaker.isOpen("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-1"))
    }

    @Test
    fun `unknown instance returns CLOSED state and zero failures`() {
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("unknown"))
        assertEquals(0, breaker.getFailureCount("unknown"))
    }

    @Test
    fun `recordSuccess on unknown instance is safe no-op`() {
        breaker.recordSuccess("unknown")
        assertFalse(breaker.isOpen("unknown"))
    }

    @Test
    fun `failure while OPEN just updates timestamp without incrementing count`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")

        val countBefore = breaker.getFailureCount("inst-1")
        breaker.recordFailure("inst-1")
        assertEquals(countBefore, breaker.getFailureCount("inst-1"))
    }
}
