package com.agnetix.harnax.router.service.impl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import java.time.Instant

/**
 * Unit tests for [RedisInstanceRegistry] using mocked RedisTemplate.
 * Uses RETURNS_DEEP_STUBS to avoid complex generic type casting issues.
 */
class RedisInstanceRegistryTest {

    @Suppress("UNCHECKED_CAST")
    private val redisTemplate: RedisTemplate<String, Any> = mock(RedisTemplate::class.java, RETURNS_DEEP_STUBS) as RedisTemplate<String, Any>

    private lateinit var registry: RedisInstanceRegistry

    @BeforeEach
    fun setUp() {
        registry = RedisInstanceRegistry(redisTemplate, heartbeatTimeoutMs = 30000)
    }

    private fun mockHashEntries(key: String, entries: Map<String, Any>) {
        `when`(redisTemplate.opsForHash<String, Any>().entries(key)).thenReturn(entries)
    }

    // ==================== registerInstance ====================

    @Nested
    inner class RegisterInstance {
        @Test
        fun `stores instance data and adds to sets`() {
            registry.registerInstance("inst-1", "10.0.0.1", 8080)

            verify(redisTemplate.opsForHash<String, Any>()).putAll(eq("router:instance:inst-1"), any<Map<String, Any>>())
            verify(redisTemplate).expire("router:instance:inst-1", Duration.ofHours(24))
            verify(redisTemplate.opsForSet()).add("router:instances:healthy", "inst-1")
            verify(redisTemplate.opsForSet()).add("router:instances:all", "inst-1")
        }
    }

    // ==================== unregisterInstance ====================

    @Nested
    inner class UnregisterInstance {
        @Test
        fun `removes instance key and set memberships`() {
            registry.unregisterInstance("inst-1")

            verify(redisTemplate).delete("router:instance:inst-1")
            verify(redisTemplate.opsForSet()).remove("router:instances:healthy", "inst-1")
            verify(redisTemplate.opsForSet()).remove("router:instances:all", "inst-1")
        }
    }

    // refreshHeartbeat / markInstanceDown / markAsDraining are single Lua scripts now; mocking
    // RedisTemplate would only assert that we called execute(). They are covered against a real
    // Redis in RedisInstanceRegistryIntegrationTest.

    // ==================== getInstance ====================

    @Nested
    inner class GetInstance {
        @Test
        fun `returns null when no hash entries`() {
            mockHashEntries("router:instance:unknown", emptyMap())
            assertNull(registry.getInstance("unknown"))
        }

        @Test
        fun `reconstructs AgentInstance from hash data`() {
            val data = mapOf<String, Any>(
                "host" to "10.0.0.1",
                "port" to 8080,
                "status" to "UP",
                "active" to 1,
                "lastHeartbeat" to "2025-01-01T12:00:00",
            )
            mockHashEntries("router:instance:inst-1", data)

            val instance = registry.getInstance("inst-1")

            assertNotNull(instance)
            assertEquals("inst-1", instance!!.instanceId)
            assertEquals("10.0.0.1", instance.host)
            assertEquals(8080, instance.port)
            assertEquals("UP", instance.status)
            assertEquals(1, instance.active)
        }

        @Test
        fun `handles missing optional fields with defaults`() {
            mockHashEntries("router:instance:inst-1", mapOf("host" to "10.0.0.1"))

            val instance = registry.getInstance("inst-1")

            assertNotNull(instance)
            assertEquals(0, instance!!.port)
            assertEquals("UP", instance.status)
            assertEquals(1, instance.active)
        }

        @Test
        fun `handles invalid heartbeat timestamp gracefully`() {
            val data = mapOf<String, Any>(
                "host" to "10.0.0.1",
                "port" to 8080,
                "status" to "UP",
                "active" to 1,
                "lastHeartbeat" to "invalid-timestamp",
            )
            mockHashEntries("router:instance:inst-1", data)

            val before = Instant.now().minusSeconds(1)
            val instance = registry.getInstance("inst-1")
            val after = Instant.now().plusSeconds(1)

            assertNotNull(instance)
            assertNotNull(instance!!.lastHeartbeat)
            // Invalid timestamp should fall back to approximately now
            assertTrue(
                instance.lastHeartbeat.isAfter(before) && instance.lastHeartbeat.isBefore(after),
                "Fallback heartbeat should be close to now, got: ${instance.lastHeartbeat}",
            )
        }
    }

    // ==================== getHealthyInstances ====================

    @Nested
    inner class GetHealthyInstances {
        @Test
        fun `returns empty list when no healthy members`() {
            `when`(redisTemplate.opsForSet().members("router:instances:healthy")).thenReturn(emptySet())
            assertTrue(registry.getHealthyInstances().isEmpty())
        }

        @Test
        fun `returns empty list when members is null`() {
            `when`(redisTemplate.opsForSet().members("router:instances:healthy")).thenReturn(null)
            assertTrue(registry.getHealthyInstances().isEmpty())
        }

        @Test
        fun `returns healthy instances`() {
            val healthyData = mapOf<String, Any>(
                "instanceId" to "inst-1",
                "host" to "10.0.0.1",
                "port" to 8080,
                "status" to "UP",
                "active" to 1,
                "lastHeartbeat" to Instant.now().toString(),
            )
            `when`(redisTemplate.opsForSet().members("router:instances:healthy")).thenReturn(setOf("inst-1"))
            mockHashEntries("router:instance:inst-1", healthyData)

            val result = registry.getHealthyInstances()

            assertEquals(1, result.size)
            assertEquals("inst-1", result[0].instanceId)
        }
    }

    // ==================== getAllActiveInstances ====================

    @Nested
    inner class GetAllActiveInstances {
        @Test
        fun `returns empty list when no members`() {
            `when`(redisTemplate.opsForSet().members("router:instances:all")).thenReturn(null)
            assertTrue(registry.getAllActiveInstances().isEmpty())
        }

        @Test
        fun `maps all members to AgentInstance`() {
            `when`(redisTemplate.opsForSet().members("router:instances:all")).thenReturn(setOf("inst-1"))
            mockHashEntries(
                "router:instance:inst-1",
                mapOf("host" to "10.0.0.1", "port" to 8080, "status" to "UP", "active" to 1, "lastHeartbeat" to "2025-01-01T00:00:00"),
            )

            val result = registry.getAllActiveInstances()

            assertEquals(1, result.size)
            assertEquals("inst-1", result[0].instanceId)
        }
    }

    // ==================== outage degradation ====================

    @Nested
    inner class DegradedFleet {
        /** What a healthy fleet looks like to the registry until Redis is taken away again. */
        private fun registeredInRedis() {
            `when`(redisTemplate.opsForSet().members("router:instances:healthy")).thenReturn(setOf("inst-1"))
            mockHashEntries(
                "router:instance:inst-1",
                mapOf("host" to "10.0.0.1", "port" to 8080, "status" to "UP", "active" to 1, "lastHeartbeat" to Instant.now().toString()),
            )
        }

        private fun redisIsDown() {
            `when`(redisTemplate.opsForSet().members("router:instances:healthy")).thenThrow(RuntimeException("Redis is down"))
        }

        @Test
        fun `routing continues on the last fleet Redis reported`() {
            registeredInRedis()
            assertEquals(1, registry.getHealthyInstances().size)

            redisIsDown()

            assertEquals(listOf("inst-1"), registry.getHealthyInstances().map { it.instanceId })
        }

        @Test
        fun `an instance Redis has already dropped is not kept alive by the snapshot`() {
            registeredInRedis()
            assertEquals(1, registry.getHealthyInstances().size)

            `when`(redisTemplate.opsForSet().members("router:instances:healthy")).thenReturn(emptySet())
            assertTrue(registry.getHealthyInstances().isEmpty())

            redisIsDown()
            assertTrue(
                registry.getHealthyInstances().isEmpty(),
                "the snapshot mirrors what Redis last confirmed, not what this node once hoped for",
            )
        }

        @Test
        fun `a session's bound instance is still named while Redis is down`() {
            registeredInRedis()
            registry.getHealthyInstances()

            `when`(redisTemplate.opsForHash<String, Any>().entries("router:instance:inst-1")).thenThrow(RuntimeException("Redis is down"))

            assertEquals("inst-1", registry.getInstance("inst-1")?.instanceId)
        }

        @Test
        fun `the health checker is never shown the snapshot`() {
            registeredInRedis()
            registry.getHealthyInstances()
            `when`(redisTemplate.opsForSet().members("router:instances:all")).thenReturn(setOf("inst-1"))
            `when`(redisTemplate.opsForHash<String, Any>().entries("router:instance:inst-1")).thenThrow(RuntimeException("Redis is down"))

            // Pruning and marking instances down must not be decided from stale data, so this read
            // fails the way any other Redis read does.
            assertThrows(RuntimeException::class.java) { registry.getAllActiveInstances() }
        }
    }
}
