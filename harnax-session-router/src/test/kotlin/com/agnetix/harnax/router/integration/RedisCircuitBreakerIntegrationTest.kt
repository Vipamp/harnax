package com.agnetix.harnax.router.integration

import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import com.agnetix.harnax.router.service.impl.RedisCircuitBreaker
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RedisCircuitBreakerIntegrationTest : RedisIntegrationTestBase() {

    private lateinit var breaker: RedisCircuitBreaker

    @BeforeEach
    fun setUp() {
        flushRedis()
        breaker = RedisCircuitBreaker(getRedisTemplate(), failureThreshold = 3, openDurationMs = 500)
    }

    @Test
    fun `initial state is CLOSED and not open`() {
        assertFalse(breaker.isOpen("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-1"))
        assertEquals(0, breaker.getFailureCount("inst-1"))
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
        assertFalse(breaker.isOpen("inst-1"))

        breaker.recordFailure("inst-1")
        assertTrue(breaker.isOpen("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.OPEN, breaker.getState("inst-1"))
    }

    @Test
    fun `instances are independent`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")

        assertTrue(breaker.isOpen("inst-1"))
        assertFalse(breaker.isOpen("inst-2"))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-2"))
    }

    @Test
    fun `OPEN transitions to HALF_OPEN after timeout`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        assertTrue(breaker.isOpen("inst-1"))

        Thread.sleep(600)

        assertFalse(breaker.isOpen("inst-1"), "Should transition to HALF_OPEN after timeout")
    }

    @Test
    fun `failure in HALF_OPEN goes back to OPEN`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        assertTrue(breaker.isOpen("inst-1"))

        Thread.sleep(600)
        assertFalse(breaker.isOpen("inst-1"))

        breaker.recordFailure("inst-1")
        assertTrue(breaker.isOpen("inst-1"), "Should go back to OPEN after failure in HALF_OPEN")
    }

    @Test
    fun `success in HALF_OPEN closes circuit`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        assertTrue(breaker.isOpen("inst-1"))

        Thread.sleep(600)
        assertFalse(breaker.isOpen("inst-1"))

        breaker.recordSuccess("inst-1")
        assertFalse(breaker.isOpen("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-1"))
        assertEquals(0, breaker.getFailureCount("inst-1"))
    }

    @Test
    fun `recordSuccess resets failure count`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        assertEquals(2, breaker.getFailureCount("inst-1"))

        breaker.recordSuccess("inst-1")
        assertEquals(0, breaker.getFailureCount("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-1"))
    }

    @Test
    fun `reset clears all state`() {
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        assertTrue(breaker.isOpen("inst-1"))

        breaker.reset("inst-1")
        assertFalse(breaker.isOpen("inst-1"))
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-1"))
        assertEquals(0, breaker.getFailureCount("inst-1"))
    }

    @Test
    fun `reset on unknown instance is safe`() {
        breaker.reset("nonexistent")
        assertFalse(breaker.isOpen("nonexistent"))
    }

    @Test
    fun `failure count increments correctly`() {
        assertEquals(0, breaker.getFailureCount("inst-1"))
        breaker.recordFailure("inst-1")
        assertEquals(1, breaker.getFailureCount("inst-1"))
        breaker.recordFailure("inst-1")
        assertEquals(2, breaker.getFailureCount("inst-1"))
        breaker.recordFailure("inst-1")
        assertEquals(3, breaker.getFailureCount("inst-1"))
    }

    @Test
    fun `full lifecycle CLOSED to OPEN to HALF_OPEN to CLOSED`() {
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-1"))

        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        breaker.recordFailure("inst-1")
        assertEquals(InstanceCircuitBreaker.State.OPEN, breaker.getState("inst-1"))
        assertTrue(breaker.isOpen("inst-1"))

        Thread.sleep(600)
        assertFalse(breaker.isOpen("inst-1"))

        breaker.recordSuccess("inst-1")
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-1"))
        assertEquals(0, breaker.getFailureCount("inst-1"))
        assertFalse(breaker.isOpen("inst-1"))

        breaker.recordFailure("inst-1")
        assertEquals(1, breaker.getFailureCount("inst-1"))
        assertFalse(breaker.isOpen("inst-1"))
    }
}
