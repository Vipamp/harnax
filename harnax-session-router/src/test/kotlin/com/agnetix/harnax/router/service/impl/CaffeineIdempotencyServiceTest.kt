package com.agnetix.harnax.router.service.impl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CaffeineIdempotencyServiceTest {

    private val service = CaffeineIdempotencyService()

    @Test
    fun `tryAcquire returns true on first call`() {
        assertTrue(service.tryAcquire("req-1"))
    }

    @Test
    fun `tryAcquire returns false on duplicate call`() {
        assertTrue(service.tryAcquire("req-2"))
        assertFalse(service.tryAcquire("req-2"))
    }

    @Test
    fun `tryAcquire allows different request IDs`() {
        assertTrue(service.tryAcquire("req-a"))
        assertTrue(service.tryAcquire("req-b"))
        assertTrue(service.tryAcquire("req-c"))
    }

    @Test
    fun `tryAcquire rejects same request ID multiple times`() {
        assertTrue(service.tryAcquire("req-dup"))
        assertFalse(service.tryAcquire("req-dup"))
        assertFalse(service.tryAcquire("req-dup"))
    }

    @Test
    fun `release lets the same request ID through again`() {
        assertTrue(service.tryAcquire("req-rel"))
        assertFalse(service.tryAcquire("req-rel"))

        service.release("req-rel")

        assertTrue(service.tryAcquire("req-rel"))
    }

    @Test
    fun `releasing a request ID nobody holds is harmless`() {
        assertDoesNotThrow { service.release("never-acquired") }
    }

    @Test
    fun `tryAcquire with empty string request ID`() {
        assertTrue(service.tryAcquire(""))
        assertFalse(service.tryAcquire(""))
    }

    @Test
    fun `tryAcquire handles high volume of unique requests`() {
        val results = (1..1000).map { service.tryAcquire("req-$it") }
        assertTrue(results.all { it })
    }

    @Test
    fun `tryAcquire rejects duplicates even after many unique requests`() {
        service.tryAcquire("first")
        (1..500).forEach { service.tryAcquire("bulk-$it") }
        assertFalse(service.tryAcquire("first"))
    }

    @Test
    fun `tryAcquire is thread-safe under concurrent access`() {
        val threadCount = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        repeat(threadCount) {
            executor.submit {
                try {
                    if (service.tryAcquire("concurrent-req")) {
                        successCount.incrementAndGet()
                    } else {
                        failCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertEquals(1, successCount.get(), "Exactly one thread should acquire the lock")
        assertEquals(threadCount - 1, failCount.get(), "All other threads should be rejected")
    }

    @Test
    fun `tryAcquire handles concurrent different request IDs`() {
        val threadCount = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)

        repeat(threadCount) { i ->
            executor.submit {
                try {
                    if (service.tryAcquire("unique-req-$i")) {
                        successCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertEquals(threadCount, successCount.get(), "All unique request IDs should be accepted")
    }
}
