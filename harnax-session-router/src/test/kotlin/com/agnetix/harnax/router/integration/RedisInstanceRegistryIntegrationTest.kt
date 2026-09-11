package com.agnetix.harnax.router.integration

import com.agnetix.harnax.router.service.impl.RedisInstanceRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RedisInstanceRegistryIntegrationTest : RedisIntegrationTestBase() {

    private lateinit var registry: RedisInstanceRegistry

    @BeforeEach
    fun setUp() {
        flushRedis()
        registry = RedisInstanceRegistry(getRedisTemplate(), heartbeatTimeoutMs = 30000)
    }

    @Test
    fun `register and retrieve instance`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)

        val instance = registry.getInstance("inst-1")
        assertNotNull(instance)
        assertEquals("inst-1", instance!!.instanceId)
        assertEquals("10.0.0.1", instance.host)
        assertEquals(8082, instance.port)
        assertEquals("UP", instance.status)
        assertEquals(1, instance.active)
    }

    @Test
    fun `register multiple instances`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.registerInstance("inst-2", "10.0.0.2", 8083)
        registry.registerInstance("inst-3", "10.0.0.3", 8084)

        val all = registry.getAllActiveInstances()
        assertEquals(3, all.size)

        val healthy = registry.getHealthyInstances()
        assertEquals(3, healthy.size)
    }

    @Test
    fun `unregister removes instance completely`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.registerInstance("inst-2", "10.0.0.2", 8083)

        registry.unregisterInstance("inst-1")

        assertNull(registry.getInstance("inst-1"))
        assertNotNull(registry.getInstance("inst-2"))
        assertEquals(1, registry.getAllActiveInstances().size)
        assertEquals(1, registry.getHealthyInstances().size)
    }

    @Test
    fun `heartbeat refresh updates timestamp`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)

        val before = registry.getInstance("inst-1")!!.lastHeartbeat
        Thread.sleep(100)
        assertTrue(registry.refreshHeartbeat("inst-1"))
        val after = registry.getInstance("inst-1")!!.lastHeartbeat

        assertTrue(after.isAfter(before), "Heartbeat should move lastHeartbeat forward")
    }

    @Test
    fun `heartbeat returns false for unknown instance`() {
        // The agent must learn about a lost registration instead of heartbeating into the void.
        assertFalse(registry.refreshHeartbeat("nonexistent"))
        assertNull(registry.getInstance("nonexistent"))
    }

    @Test
    fun `heartbeat does not resurrect a draining instance`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.markAsDraining("inst-1")

        assertTrue(registry.refreshHeartbeat("inst-1"))

        assertEquals("DRAINING", registry.getInstance("inst-1")!!.status)
        assertEquals(0, registry.getHealthyInstances().size, "Draining instance must not take new sessions")
    }

    @Test
    fun `heartbeat recovers a DOWN instance`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.markInstanceDown("inst-1")
        assertEquals(0, registry.getHealthyInstances().size)

        assertTrue(registry.refreshHeartbeat("inst-1"))

        assertEquals("UP", registry.getInstance("inst-1")!!.status)
        assertEquals(1, registry.getHealthyInstances().size)
    }

    @Test
    fun `stale instance stays discoverable after being skipped by getHealthyInstances`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        val shortTimeoutRegistry = RedisInstanceRegistry(getRedisTemplate(), heartbeatTimeoutMs = 1)
        Thread.sleep(50)

        assertEquals(0, shortTimeoutRegistry.getHealthyInstances().size)

        // A heartbeat proves the instance is alive again; the id must still be in the healthy set
        // so the Lua heartbeat can flip its status back to UP.
        Thread.sleep(50)
        assertTrue(registry.refreshHeartbeat("inst-1"))
        assertEquals(1, registry.getHealthyInstances().size)
    }

    @Test
    fun `getHealthyInstances filters stale instances`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)

        // Use a short timeout to make inst-1 appear stale
        val shortTimeoutRegistry = RedisInstanceRegistry(getRedisTemplate(), heartbeatTimeoutMs = 1)
        Thread.sleep(50)

        val healthy = shortTimeoutRegistry.getHealthyInstances()
        assertEquals(0, healthy.size, "Stale instance should not be healthy")
    }

    @Test
    fun `getHealthyInstances prunes ids whose registration is gone`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        // Simulate the 24h key TTL expiring while the id lingers in the set.
        getRedisTemplate().delete("router:instance:inst-1")

        assertEquals(0, registry.getHealthyInstances().size)
        assertFalse(getRedisTemplate().opsForSet().isMember("router:instances:healthy", "inst-1") == true)
    }

    @Test
    fun `markInstanceDown changes status and removes from healthy`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        assertEquals(1, registry.getHealthyInstances().size)

        val result = registry.markInstanceDown("inst-1")
        assertEquals(1, result)

        val instance = registry.getInstance("inst-1")
        assertEquals("DOWN", instance!!.status)
        assertEquals(0, registry.getHealthyInstances().size)
        // getHealthyInstances filters on status too, so only the raw set proves the script's SREM
        // matched the stored member rather than the read path hiding it.
        assertEquals(false, getRedisTemplate().opsForSet().isMember("router:instances:healthy", "inst-1"))
        assertEquals(true, getRedisTemplate().opsForSet().isMember("router:instances:all", "inst-1"))
    }

    @Test
    fun `markInstanceDown keeps the registration so the instance can come back`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.markInstanceDown("inst-1")

        assertNotNull(registry.getInstance("inst-1"))
        assertEquals(1, registry.getAllActiveInstances().size)
    }

    @Test
    fun `markInstanceDown on unknown instance returns 0`() {
        assertEquals(0, registry.markInstanceDown("nonexistent"))
    }

    @Test
    fun `markInstanceDown on already DOWN instance returns 0`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.markInstanceDown("inst-1")
        assertEquals(0, registry.markInstanceDown("inst-1"))
    }

    @Test
    fun `only one concurrent markInstanceDown wins`() {
        // Every router node runs the health checker on the same schedule; if two of them both
        // "won", both would migrate the same sessions.
        registry.registerInstance("inst-1", "10.0.0.1", 8082)

        val threads = 8
        val start = java.util.concurrent.CountDownLatch(1)
        val winners = java.util.concurrent.atomic.AtomicInteger(0)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.submit {
                start.await()
                if (registry.markInstanceDown("inst-1") == 1) winners.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))

        assertEquals(1, winners.get(), "Exactly one caller may perform the UP -> DOWN transition")
    }

    @Test
    fun `markAsDraining sets DRAINING status and removes from healthy`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        assertEquals(1, registry.getHealthyInstances().size)

        assertTrue(registry.markAsDraining("inst-1"))

        val instance = registry.getInstance("inst-1")
        assertEquals("DRAINING", instance!!.status)
        assertEquals(0, registry.getHealthyInstances().size)
        // Still in all active
        assertEquals(1, registry.getAllActiveInstances().size)
    }

    @Test
    fun `markAsDraining on unknown instance returns false and registers nothing`() {
        assertFalse(registry.markAsDraining("nonexistent"))
        assertNull(registry.getInstance("nonexistent"))
    }

    @Test
    fun `getInstance returns null for nonexistent`() {
        assertNull(registry.getInstance("ghost"))
    }

    @Test
    fun `re-register overwrites existing instance`() {
        registry.registerInstance("inst-1", "10.0.0.1", 8082)
        registry.registerInstance("inst-1", "10.0.0.99", 9999)

        val instance = registry.getInstance("inst-1")!!
        assertEquals("10.0.0.99", instance.host)
        assertEquals(9999, instance.port)
        assertEquals(1, registry.getAllActiveInstances().size)
    }
}
