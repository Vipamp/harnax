package com.agnetix.harnax.router.integration

import com.agnetix.harnax.router.service.impl.RedisIdempotencyService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RedisIdempotencyServiceIntegrationTest : RedisIntegrationTestBase() {

    private lateinit var service: RedisIdempotencyService

    @BeforeEach
    fun setUp() {
        flushRedis()
        service = RedisIdempotencyService(getRedisTemplate(), ttlSeconds = 60)
    }

    @Test
    fun `first request is accepted`() {
        assertTrue(service.tryAcquire("req-1"))
    }

    @Test
    fun `duplicate request is rejected`() {
        assertTrue(service.tryAcquire("req-2"))
        assertFalse(service.tryAcquire("req-2"))
    }

    @Test
    fun `different request IDs are independent`() {
        assertTrue(service.tryAcquire("req-a"))
        assertTrue(service.tryAcquire("req-b"))
        assertTrue(service.tryAcquire("req-c"))
    }

    @Test
    fun `multiple rejections on same ID`() {
        assertTrue(service.tryAcquire("req-dup"))
        assertFalse(service.tryAcquire("req-dup"))
        assertFalse(service.tryAcquire("req-dup"))
        assertFalse(service.tryAcquire("req-dup"))
    }

    @Test
    fun `high volume unique requests all succeed`() {
        repeat(100) { i ->
            assertTrue(service.tryAcquire("bulk-$i"), "Request bulk-$i should succeed")
        }
    }

    @Test
    fun `expired TTL allows same ID again`() {
        val shortTtlService = RedisIdempotencyService(getRedisTemplate(), ttlSeconds = 1)

        assertTrue(shortTtlService.tryAcquire("expiring"))
        assertFalse(shortTtlService.tryAcquire("expiring"))

        Thread.sleep(1100)

        assertTrue(shortTtlService.tryAcquire("expiring"), "Should succeed after TTL expires")
    }

    @Test
    fun `empty string request ID works`() {
        assertTrue(service.tryAcquire(""))
        assertFalse(service.tryAcquire(""))
    }

    @Test
    fun `long request ID works`() {
        val longId = "x".repeat(200)
        assertTrue(service.tryAcquire(longId))
        assertFalse(service.tryAcquire(longId))
    }
}
