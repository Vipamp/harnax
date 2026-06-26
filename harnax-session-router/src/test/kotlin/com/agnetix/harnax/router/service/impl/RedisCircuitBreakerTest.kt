package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [RedisCircuitBreaker] using mocked RedisTemplate.
 * Verifies correct Redis key patterns and state machine logic.
 */
class RedisCircuitBreakerTest {

    @Suppress("UNCHECKED_CAST")
    private val redisTemplate: RedisTemplate<String, Any> = mock(RedisTemplate::class.java, RETURNS_DEEP_STUBS) as RedisTemplate<String, Any>
    private val valueOps: ValueOperations<String, Any> = mock(ValueOperations::class.java) as ValueOperations<String, Any>

    private lateinit var breaker: RedisCircuitBreaker

    @BeforeEach
    fun setUp() {
        `when`(redisTemplate.opsForValue()).thenReturn(valueOps)
        breaker = RedisCircuitBreaker(redisTemplate, failureThreshold = 3, openDurationMs = 30000)
    }

    // ==================== isOpen ====================

    @Nested
    inner class IsOpen {
        @Test
        fun `returns false when no state in redis`() {
            `when`(valueOps.get(any<String>())).thenReturn(null)
            assertFalse(breaker.isOpen("inst-1"))
        }

        @Test
        fun `returns true when state is OPEN and within timeout`() {
            val stateKey = "router:circuit:inst-1:state"
            val lastFailureKey = "router:circuit:inst-1:last_failure"

            `when`(valueOps.get(stateKey)).thenReturn("OPEN")
            `when`(valueOps.get(lastFailureKey)).thenReturn(System.currentTimeMillis())

            assertTrue(breaker.isOpen("inst-1"))
        }

        @Test
        fun `returns false and transitions to HALF_OPEN when OPEN and timeout elapsed`() {
            val stateKey = "router:circuit:inst-1:state"
            val lastFailureKey = "router:circuit:inst-1:last_failure"

            `when`(valueOps.get(stateKey)).thenReturn("OPEN")
            `when`(valueOps.get(lastFailureKey)).thenReturn(System.currentTimeMillis() - 60000)
            `when`(valueOps.setIfAbsent(eq(stateKey), eq("HALF_OPEN"), eq(30000L), eq(TimeUnit.MILLISECONDS))).thenReturn(true)

            assertFalse(breaker.isOpen("inst-1"))
            verify(valueOps).setIfAbsent(stateKey, "HALF_OPEN", 30000L, TimeUnit.MILLISECONDS)
        }

        @Test
        fun `returns false when state is HALF_OPEN`() {
            val stateKey = "router:circuit:inst-1:state"
            `when`(valueOps.get(stateKey)).thenReturn("HALF_OPEN")

            assertFalse(breaker.isOpen("inst-1"))
        }

        @Test
        fun `returns false when state is CLOSED`() {
            val stateKey = "router:circuit:inst-1:state"
            `when`(valueOps.get(stateKey)).thenReturn("CLOSED")

            assertFalse(breaker.isOpen("inst-1"))
        }
    }

    // ==================== recordFailure ====================

    @Nested
    inner class RecordFailure {
        @Test
        fun `increments failure count and sets last failure time`() {
            val failuresKey = "router:circuit:inst-1:failures"
            val lastFailureKey = "router:circuit:inst-1:last_failure"
            val stateKey = "router:circuit:inst-1:state"

            `when`(valueOps.increment(failuresKey)).thenReturn(1L)
            `when`(valueOps.get(stateKey)).thenReturn("CLOSED")

            breaker.recordFailure("inst-1")

            verify(valueOps).set(eq(lastFailureKey), any<Long>())
            verify(valueOps).increment(failuresKey)
        }

        @Test
        fun `opens circuit when failures reach threshold`() {
            val failuresKey = "router:circuit:inst-1:failures"
            val stateKey = "router:circuit:inst-1:state"

            `when`(valueOps.increment(failuresKey)).thenReturn(3L)
            `when`(valueOps.get(stateKey)).thenReturn("CLOSED")

            breaker.recordFailure("inst-1")

            verify(valueOps).set(stateKey, "OPEN", 30000L, TimeUnit.MILLISECONDS)
        }

        @Test
        fun `does not open circuit when below threshold`() {
            val failuresKey = "router:circuit:inst-1:failures"
            val stateKey = "router:circuit:inst-1:state"

            `when`(valueOps.increment(failuresKey)).thenReturn(2L)
            `when`(valueOps.get(stateKey)).thenReturn("CLOSED")

            breaker.recordFailure("inst-1")

            verify(valueOps, never()).set(eq(stateKey), eq("OPEN"), any(), any())
        }

        @Test
        fun `immediately reopens circuit on HALF_OPEN failure`() {
            val stateKey = "router:circuit:inst-1:state"

            `when`(valueOps.increment(any<String>())).thenReturn(1L)
            `when`(valueOps.get(stateKey)).thenReturn("HALF_OPEN")

            breaker.recordFailure("inst-1")

            verify(valueOps).set(stateKey, "OPEN", 30000L, TimeUnit.MILLISECONDS)
        }

        @Test
        fun `updates TTL when already OPEN`() {
            val lastFailureKey = "router:circuit:inst-1:last_failure"
            val stateKey = "router:circuit:inst-1:state"

            `when`(valueOps.increment(any<String>())).thenReturn(1L)
            `when`(valueOps.get(stateKey)).thenReturn("OPEN")

            breaker.recordFailure("inst-1")

            verify(redisTemplate).expire(lastFailureKey, 30000L, TimeUnit.MILLISECONDS)
        }
    }

    // ==================== recordSuccess ====================

    @Nested
    inner class RecordSuccess {
        @Test
        fun `sets state to CLOSED and clears failure count`() {
            val stateKey = "router:circuit:inst-1:state"
            val failuresKey = "router:circuit:inst-1:failures"

            `when`(valueOps.getAndSet(stateKey, "CLOSED")).thenReturn("HALF_OPEN")

            breaker.recordSuccess("inst-1")

            verify(valueOps).getAndSet(stateKey, "CLOSED")
            verify(redisTemplate).delete(failuresKey)
        }

        @Test
        fun `works when previous state is null`() {
            val stateKey = "router:circuit:inst-1:state"
            val failuresKey = "router:circuit:inst-1:failures"

            `when`(valueOps.getAndSet(stateKey, "CLOSED")).thenReturn(null)

            breaker.recordSuccess("inst-1")

            verify(redisTemplate).delete(failuresKey)
        }
    }

    // ==================== reset ====================

    @Nested
    inner class Reset {
        @Test
        fun `deletes all three keys`() {
            breaker.reset("inst-1")

            verify(redisTemplate).delete("router:circuit:inst-1:state")
            verify(redisTemplate).delete("router:circuit:inst-1:failures")
            verify(redisTemplate).delete("router:circuit:inst-1:last_failure")
        }
    }

    // ==================== getState ====================

    @Nested
    inner class GetState {
        @Test
        fun `returns CLOSED when no state in redis`() {
            `when`(valueOps.get(any<String>())).thenReturn(null)
            assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-1"))
        }

        @Test
        fun `returns OPEN`() {
            `when`(valueOps.get("router:circuit:inst-1:state")).thenReturn("OPEN")
            assertEquals(InstanceCircuitBreaker.State.OPEN, breaker.getState("inst-1"))
        }

        @Test
        fun `returns HALF_OPEN`() {
            `when`(valueOps.get("router:circuit:inst-1:state")).thenReturn("HALF_OPEN")
            assertEquals(InstanceCircuitBreaker.State.HALF_OPEN, breaker.getState("inst-1"))
        }

        @Test
        fun `returns CLOSED for unknown string`() {
            `when`(valueOps.get("router:circuit:inst-1:state")).thenReturn("UNKNOWN")
            assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState("inst-1"))
        }
    }

    // ==================== getFailureCount ====================

    @Nested
    inner class GetFailureCount {
        @Test
        fun `returns 0 when no failures`() {
            `when`(valueOps.get(any<String>())).thenReturn(null)
            assertEquals(0, breaker.getFailureCount("inst-1"))
        }

        @Test
        fun `returns failure count from redis`() {
            `when`(valueOps.get("router:circuit:inst-1:failures")).thenReturn(5)
            assertEquals(5, breaker.getFailureCount("inst-1"))
        }

        @Test
        fun `handles Long type from redis`() {
            `when`(valueOps.get("router:circuit:inst-1:failures")).thenReturn(7L)
            assertEquals(7, breaker.getFailureCount("inst-1"))
        }
    }
}
