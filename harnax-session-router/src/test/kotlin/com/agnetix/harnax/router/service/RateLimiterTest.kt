package com.agnetix.harnax.router.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class RateLimiterTest {

    private lateinit var rateLimiter: RateLimiter

    @BeforeEach
    fun setUp() {
        rateLimiter = RateLimiter()
    }

    @Test
    fun `first acquire returns true`() {
        assertTrue(rateLimiter.tryAcquire("key-1", 10))
    }

    @Test
    fun `acquire within limit always succeeds`() {
        val limit = 5
        for (i in 1..limit) {
            assertTrue(rateLimiter.tryAcquire("key-a", limit), "Attempt $i should succeed")
        }
    }

    @Test
    fun `acquire beyond limit returns false`() {
        val limit = 3
        assertTrue(rateLimiter.tryAcquire("key-b", limit))
        assertTrue(rateLimiter.tryAcquire("key-b", limit))
        assertTrue(rateLimiter.tryAcquire("key-b", limit))
        assertFalse(rateLimiter.tryAcquire("key-b", limit), "4th request should be rejected")
    }

    @Test
    fun `multiple rejections after limit reached`() {
        val limit = 2
        assertTrue(rateLimiter.tryAcquire("key-c", limit))
        assertTrue(rateLimiter.tryAcquire("key-c", limit))
        assertFalse(rateLimiter.tryAcquire("key-c", limit))
        assertFalse(rateLimiter.tryAcquire("key-c", limit))
        assertFalse(rateLimiter.tryAcquire("key-c", limit))
    }

    @Test
    fun `different keys are independent`() {
        val limit = 2
        assertTrue(rateLimiter.tryAcquire("key-x", limit))
        assertTrue(rateLimiter.tryAcquire("key-x", limit))
        assertFalse(rateLimiter.tryAcquire("key-x", limit))

        assertTrue(rateLimiter.tryAcquire("key-y", limit), "Different key should not be affected")
        assertTrue(rateLimiter.tryAcquire("key-y", limit))
        assertFalse(rateLimiter.tryAcquire("key-y", limit))
    }

    @Test
    fun `limit of 1 allows exactly one request`() {
        assertTrue(rateLimiter.tryAcquire("solo", 1))
        assertFalse(rateLimiter.tryAcquire("solo", 1))
    }

    @Test
    fun `getCurrentCount returns 0 for unknown key`() {
        assertEquals(0, rateLimiter.getCurrentCount("nonexistent"))
    }

    @Test
    fun `getCurrentCount reflects acquired requests`() {
        rateLimiter.tryAcquire("counted", 100)
        assertEquals(1, rateLimiter.getCurrentCount("counted"))

        rateLimiter.tryAcquire("counted", 100)
        rateLimiter.tryAcquire("counted", 100)
        assertEquals(3, rateLimiter.getCurrentCount("counted"))
    }

    @Test
    fun `cleanup removes empty windows`() {
        // No keys to clean — should not throw
        rateLimiter.cleanup()

        // Add a key, verify it's still present after cleanup
        rateLimiter.tryAcquire("persist", 100)
        rateLimiter.cleanup()
        assertEquals(1, rateLimiter.getCurrentCount("persist"))
    }

    @Test
    fun `high volume many unique keys`() {
        for (i in 1..500) {
            assertTrue(rateLimiter.tryAcquire("bulk-$i", 1))
        }
        assertEquals(1, rateLimiter.getCurrentCount("bulk-250"))
    }

    @Test
    fun `concurrent acquire same key exactly limit wins`() {
        val limit = 10
        val threads = 30
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        val futures = (1..threads).map {
            executor.submit {
                latch.await()
                if (rateLimiter.tryAcquire("concurrent-key", limit)) {
                    successCount.incrementAndGet()
                } else {
                    failCount.incrementAndGet()
                }
            }
        }

        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        assertEquals(limit, successCount.get(), "Exactly $limit threads should succeed")
        assertEquals(threads - limit, failCount.get())
    }

    @Test
    fun `concurrent acquire different keys all succeed`() {
        val threads = 50
        val latch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        val successCount = AtomicInteger(0)

        val futures = (1..threads).map { i ->
            executor.submit {
                latch.await()
                if (rateLimiter.tryAcquire("iso-$i", 1)) {
                    successCount.incrementAndGet()
                }
            }
        }

        latch.countDown()
        futures.forEach { it.get() }
        executor.shutdown()

        assertEquals(threads, successCount.get(), "All unique keys should succeed")
    }
}
