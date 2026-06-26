package com.agnetix.harnax.router.service.impl

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
 * Unit tests for [RedisIdempotencyService] using mocked RedisTemplate.
 */
class RedisIdempotencyServiceTest {

    @Suppress("UNCHECKED_CAST")
    private val redisTemplate: RedisTemplate<String, Any> = mock(RedisTemplate::class.java, RETURNS_DEEP_STUBS) as RedisTemplate<String, Any>
    private val valueOps: ValueOperations<String, Any> = mock(ValueOperations::class.java) as ValueOperations<String, Any>

    private lateinit var service: RedisIdempotencyService

    @BeforeEach
    fun setUp() {
        `when`(redisTemplate.opsForValue()).thenReturn(valueOps)
        service = RedisIdempotencyService(redisTemplate, ttlSeconds = 60)
    }

    @Nested
    inner class TryAcquire {
        @Test
        fun `returns true when setIfAbsent succeeds (first request)`() {
            `when`(
                valueOps.setIfAbsent(
                    eq("router:idempotency:req-1"),
                    any<String>(),
                    eq(60L),
                    eq(TimeUnit.SECONDS),
                ),
            ).thenReturn(true)

            assertTrue(service.tryAcquire("req-1"))
        }

        @Test
        fun `returns false when setIfAbsent fails (duplicate request)`() {
            `when`(
                valueOps.setIfAbsent(
                    eq("router:idempotency:req-1"),
                    any<String>(),
                    eq(60L),
                    eq(TimeUnit.SECONDS),
                ),
            ).thenReturn(false)

            assertFalse(service.tryAcquire("req-1"))
        }

        @Test
        fun `returns false when setIfAbsent returns null`() {
            `when`(
                valueOps.setIfAbsent(
                    any<String>(),
                    any<String>(),
                    any<Long>(),
                    any<TimeUnit>(),
                ),
            ).thenReturn(null)

            assertFalse(service.tryAcquire("req-2"))
        }

        @Test
        fun `falls back to true on Redis exception`() {
            `when`(
                valueOps.setIfAbsent(
                    any<String>(),
                    any<String>(),
                    any<Long>(),
                    any<TimeUnit>(),
                ),
            ).thenThrow(RuntimeException("Redis connection refused"))

            assertTrue(service.tryAcquire("req-error"))
        }

        @Test
        fun `uses correct key prefix`() {
            `when`(
                valueOps.setIfAbsent(
                    eq("router:idempotency:my-request-id"),
                    any<String>(),
                    eq(60L),
                    eq(TimeUnit.SECONDS),
                ),
            ).thenReturn(true)

            service.tryAcquire("my-request-id")

            verify(valueOps).setIfAbsent(
                eq("router:idempotency:my-request-id"),
                any<String>(),
                eq(60L),
                eq(TimeUnit.SECONDS),
            )
        }

        @Test
        fun `respects configured TTL`() {
            val shortTtlService = RedisIdempotencyService(redisTemplate, ttlSeconds = 10)

            `when`(
                valueOps.setIfAbsent(
                    any<String>(),
                    any<String>(),
                    eq(10L),
                    eq(TimeUnit.SECONDS),
                ),
            ).thenReturn(true)

            shortTtlService.tryAcquire("req-ttl")

            verify(valueOps).setIfAbsent(
                any<String>(),
                any<String>(),
                eq(10L),
                eq(TimeUnit.SECONDS),
            )
        }
    }
}
