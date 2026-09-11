package com.agnetix.harnax.router.integration

import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import com.agnetix.harnax.router.service.InstanceCircuitBreakerContract
import com.agnetix.harnax.router.service.impl.RedisCircuitBreaker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.data.redis.connection.DataType
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the shared breaker contract against Redis, then the guarantees only a shared store can give:
 * every router node sees one circuit, and the probe slot is granted to exactly one of them.
 */
class RedisCircuitBreakerIntegrationTest : RedisIntegrationTestBase() {

    private lateinit var breaker: RedisCircuitBreaker

    @BeforeEach
    fun setUp() {
        flushRedis()
        breaker = newBreaker()
    }

    @TestFactory
    fun `breaker contract`(): List<DynamicTest> = InstanceCircuitBreakerContract(
        factory = { failureThreshold, openDurationMs, probeLeaseMs ->
            RedisCircuitBreaker(getRedisTemplate(), failureThreshold, openDurationMs, probeLeaseMs)
        },
        wipeState = { flushRedis() },
    ).tests()

    @Test
    fun `all breaker nodes share one circuit`() {
        val nodeA = newBreaker()
        val nodeB = newBreaker()

        nodeA.recordFailure(INST)
        nodeA.recordFailure(INST)
        nodeB.recordFailure(INST)

        assertTrue(nodeA.isOpen(INST), "the failure that opened the circuit came from another node")
        assertTrue(nodeB.isOpen(INST))
        assertEquals(nodeA.getState(INST), nodeB.getState(INST))
    }

    @Test
    fun `only one of the concurrent probes across nodes is granted`() {
        // When the window closes, every node learns it at the same moment. Without a shared probe
        // slot that is the instant a broken instance gets the full cluster's traffic.
        trip(breaker)
        Thread.sleep(OPEN_DURATION_MS + 80)

        val nodes = List(8) { newBreaker() }
        val start = CountDownLatch(1)
        val granted = ConcurrentLinkedQueue<Int>()
        val threads = nodes.mapIndexed { index, node ->
            Thread {
                start.await()
                if (node.allowRequest(INST)) granted.add(index)
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(1, granted.size, "exactly one node may probe, got ${granted.size}")
        assertEquals(InstanceCircuitBreaker.State.HALF_OPEN, breaker.getState(INST))
    }

    @Test
    fun `state lives in one hash that carries a TTL and disappears when healed`() {
        val redisTemplate = getRedisTemplate()
        trip(breaker)

        assertEquals(DataType.HASH, redisTemplate.type(BREAKER_KEY))
        assertTrue(
            (redisTemplate.getExpire(BREAKER_KEY, TimeUnit.MILLISECONDS) ?: 0L) > 0,
            "an open circuit must expire instead of blocking the instance forever",
        )

        breaker.recordSuccess(INST)

        assertFalse(redisTemplate.hasKey(BREAKER_KEY) == true, "a healed circuit must leave no key behind")
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState(INST))
    }

    @Test
    fun `a failure window that never reaches the threshold expires`() {
        // Below-threshold failures must not accumulate for ever: the next unrelated error would
        // otherwise open a circuit on top of a count from an hour ago.
        breaker.recordFailure(INST)
        assertEquals(1, breaker.getFailureCount(INST))
        Thread.sleep(OPEN_DURATION_MS + 80)

        assertEquals(0, breaker.getFailureCount(INST), "a stale failure window must expire")
        assertEquals(InstanceCircuitBreaker.State.CLOSED, breaker.getState(INST))
    }

    private fun newBreaker(): RedisCircuitBreaker = RedisCircuitBreaker(getRedisTemplate(), FAILURE_THRESHOLD, OPEN_DURATION_MS, PROBE_LEASE_MS)

    private fun trip(target: RedisCircuitBreaker) {
        repeat(FAILURE_THRESHOLD) { target.recordFailure(INST) }
    }

    private companion object {
        const val INST = "inst-1"
        const val BREAKER_KEY = "router:circuit:inst-1"
        const val FAILURE_THRESHOLD = 3
        const val OPEN_DURATION_MS = 300L
        const val PROBE_LEASE_MS = 200L
    }
}
