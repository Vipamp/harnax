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
import java.time.LocalDateTime

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

    private fun mockHashGet(key: String, field: String, value: Any?) {
        `when`(redisTemplate.opsForHash<String, Any>().get(key, field)).thenReturn(value)
    }

    private fun mockHashEntries(key: String, entries: Map<String, Any>) {
        `when`(redisTemplate.opsForHash<String, Any>().entries(key)).thenReturn(entries)
    }

    private fun mockHashHasKey(key: String, field: String, exists: Boolean) {
        `when`(redisTemplate.opsForHash<String, Any>().hasKey(key, field)).thenReturn(exists)
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

    // ==================== refreshHeartbeat ====================

    @Nested
    inner class RefreshHeartbeat {
        @Test
        fun `updates heartbeat when instance exists`() {
            mockHashHasKey("router:instance:inst-1", "instanceId", true)

            registry.refreshHeartbeat("inst-1")

            verify(redisTemplate.opsForHash<String, Any>()).put(eq("router:instance:inst-1"), eq("lastHeartbeat"), any<String>())
            verify(redisTemplate.opsForHash<String, Any>()).put("router:instance:inst-1", "status", "UP")
            verify(redisTemplate).expire("router:instance:inst-1", Duration.ofHours(24))
        }

        @Test
        fun `does nothing when instance not found`() {
            mockHashHasKey("router:instance:unknown", "instanceId", false)

            registry.refreshHeartbeat("unknown")

            verify(redisTemplate.opsForHash<String, Any>(), never()).put(any(), any(), any())
        }
    }

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

            val before = LocalDateTime.now().minusSeconds(1)
            val instance = registry.getInstance("inst-1")
            val after = LocalDateTime.now().plusSeconds(1)

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
                "lastHeartbeat" to LocalDateTime.now().toString(),
            )
            `when`(redisTemplate.opsForSet().members("router:instances:healthy")).thenReturn(setOf("inst-1"))
            mockHashEntries("router:instance:inst-1", healthyData)

            val result = registry.getHealthyInstances()

            assertEquals(1, result.size)
            assertEquals("inst-1", result[0].instanceId)
        }
    }

    // ==================== markInstanceDown ====================

    @Nested
    inner class MarkInstanceDown {
        @Test
        fun `returns 0 when instance not found`() {
            mockHashHasKey("router:instance:unknown", "instanceId", false)
            assertEquals(0, registry.markInstanceDown("unknown"))
        }

        @Test
        fun `returns 0 when already DOWN`() {
            mockHashHasKey("router:instance:inst-1", "instanceId", true)
            mockHashGet("router:instance:inst-1", "status", "DOWN")
            assertEquals(0, registry.markInstanceDown("inst-1"))
        }

        @Test
        fun `marks as DOWN and removes from healthy set`() {
            mockHashHasKey("router:instance:inst-1", "instanceId", true)
            mockHashGet("router:instance:inst-1", "status", "UP")

            assertEquals(1, registry.markInstanceDown("inst-1"))

            verify(redisTemplate.opsForHash<String, Any>()).put("router:instance:inst-1", "status", "DOWN")
            verify(redisTemplate.opsForSet()).remove("router:instances:healthy", "inst-1")
        }
    }

    // ==================== markAsDraining ====================

    @Nested
    inner class MarkAsDraining {
        @Test
        fun `sets status to DRAINING and removes from healthy set`() {
            registry.markAsDraining("inst-1")

            verify(redisTemplate.opsForHash<String, Any>()).put(eq("router:instance:inst-1"), eq("status"), eq("DRAINING"))
            verify(redisTemplate.opsForHash<String, Any>()).put(eq("router:instance:inst-1"), eq("lastHeartbeat"), any<String>())
            verify(redisTemplate.opsForSet()).remove("router:instances:healthy", "inst-1")
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
}
