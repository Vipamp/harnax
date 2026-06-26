package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SetOperations
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.time.LocalDateTime

/**
 * Unit tests for [RedisSessionMappingService] using mocked RedisTemplate.
 */
class RedisSessionMappingServiceTest {

    @Suppress("UNCHECKED_CAST")
    private val redisTemplate: RedisTemplate<String, Any> = mock(RedisTemplate::class.java, RETURNS_DEEP_STUBS) as RedisTemplate<String, Any>
    private val valueOps: ValueOperations<String, Any> = mock(ValueOperations::class.java) as ValueOperations<String, Any>
    private val setOps: SetOperations<String, Any> = mock(SetOperations::class.java) as SetOperations<String, Any>
    private val instanceRegistry: InstanceRegistry = mock(InstanceRegistry::class.java)

    private lateinit var service: RedisSessionMappingService

    @BeforeEach
    fun setUp() {
        `when`(redisTemplate.opsForValue()).thenReturn(valueOps)
        `when`(redisTemplate.opsForSet()).thenReturn(setOps as SetOperations<String, Any>)
        service = RedisSessionMappingService(instanceRegistry, redisTemplate, heartbeatTimeoutMs = 30000)
    }

    // ==================== bindSession ====================

    @Nested
    inner class BindSession {
        @Test
        fun `stores session-to-instance mapping in redis`() {
            `when`(valueOps.get(any<String>())).thenReturn(null)

            service.bindSession("session-1", "inst-1", null)

            verify(valueOps).set(eq("router:session:session-1"), eq("inst-1"), any<Duration>())
            verify(setOps as SetOperations<String, Any>).add("router:instance_sessions:inst-1", "session-1")
            verify(redisTemplate).expire(eq("router:instance_sessions:inst-1"), any<Duration>())
        }

        @Test
        fun `removes old binding when re-binding to different instance`() {
            `when`(valueOps.get("router:session:session-1")).thenReturn("old-inst")

            service.bindSession("session-1", "new-inst", null)

            verify(setOps as SetOperations<String, Any>).remove("router:instance_sessions:old-inst", "session-1")
            verify(valueOps).set(eq("router:session:session-1"), eq("new-inst"), any<Duration>())
        }

        @Test
        fun `does not remove old binding when same instance`() {
            `when`(valueOps.get("router:session:session-1")).thenReturn("inst-1")

            service.bindSession("session-1", "inst-1", null)

            verify(setOps as SetOperations<String, Any>, never()).remove(any<String>(), any<String>())
        }

        @Test
        fun `rejects blank session ID`() {
            assertThrows(IllegalArgumentException::class.java) {
                service.bindSession("", "inst-1", null)
            }
        }

        @Test
        fun `rejects blank instance ID`() {
            assertThrows(IllegalArgumentException::class.java) {
                service.bindSession("session-1", "", null)
            }
        }
    }

    // ==================== getInstanceId ====================

    @Nested
    inner class GetInstanceId {
        @Test
        fun `returns instance ID from redis`() {
            `when`(valueOps.get("router:session:session-1")).thenReturn("inst-1")
            assertEquals("inst-1", service.getInstanceId("session-1"))
        }

        @Test
        fun `returns null when no mapping`() {
            `when`(valueOps.get("router:session:session-1")).thenReturn(null)
            assertNull(service.getInstanceId("session-1"))
        }

        @Test
        fun `rejects blank session ID`() {
            assertThrows(IllegalArgumentException::class.java) {
                service.getInstanceId("  ")
            }
        }
    }

    // ==================== unbindSession ====================

    @Nested
    inner class UnbindSession {
        @Test
        fun `deletes session key and reverse index`() {
            `when`(valueOps.get("router:session:session-1")).thenReturn("inst-1")

            service.unbindSession("session-1")

            verify(redisTemplate).delete("router:session:session-1")
            verify(setOps as SetOperations<String, Any>).remove("router:instance_sessions:inst-1", "session-1")
        }

        @Test
        fun `handles no existing binding gracefully`() {
            `when`(valueOps.get("router:session:session-1")).thenReturn(null)

            service.unbindSession("session-1")

            verify(redisTemplate).delete("router:session:session-1")
            verify(setOps as SetOperations<String, Any>, never()).remove(any<String>(), any<String>())
        }

        @Test
        fun `rejects blank session ID`() {
            assertThrows(IllegalArgumentException::class.java) {
                service.unbindSession("")
            }
        }
    }

    // ==================== refreshActiveTime ====================

    @Nested
    inner class RefreshActiveTime {
        @Test
        fun `refreshes TTL on session key`() {
            service.refreshActiveTime("session-1")
            verify(redisTemplate).expire(eq("router:session:session-1"), any<Duration>())
        }

        @Test
        fun `rejects blank session ID`() {
            assertThrows(IllegalArgumentException::class.java) {
                service.refreshActiveTime("")
            }
        }

        @Test
        fun `swallows Redis exception gracefully`() {
            `when`(redisTemplate.expire(any<String>(), any<Duration>())).thenThrow(RuntimeException("Redis down"))

            assertDoesNotThrow {
                service.refreshActiveTime("session-1")
            }
        }
    }

    // ==================== rerouteSession ====================

    @Nested
    inner class RerouteSession {
        private fun healthyInstance(id: String): AgentInstance = AgentInstance().apply {
            instanceId = id
            host = "10.0.0.1"
            port = 8080
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now()
        }

        @Test
        fun `reroutes to a healthy instance`() {
            val instance = healthyInstance("inst-1")
            `when`(instanceRegistry.getHealthyInstances()).thenReturn(listOf(instance))
            `when`(valueOps.setIfAbsent(any<String>(), any<String>(), any<Duration>())).thenReturn(true)
            `when`(valueOps.get(any<String>())).thenReturn(null)
            `when`(setOps.size(any<String>())).thenReturn(0L)

            val result = service.rerouteSession("session-1")

            assertEquals("inst-1", result)
        }

        @Test
        fun `throws when no healthy instances available`() {
            `when`(instanceRegistry.getHealthyInstances()).thenReturn(emptyList())
            `when`(valueOps.setIfAbsent(any<String>(), any<String>(), any<Duration>())).thenReturn(true)

            assertThrows(IllegalStateException::class.java) {
                service.rerouteSession("session-1")
            }
        }

        @Test
        fun `rejects blank session ID`() {
            assertThrows(IllegalArgumentException::class.java) {
                service.rerouteSession("")
            }
        }
    }

    // ==================== rebindAllSessions ====================

    @Nested
    inner class RebindAllSessions {
        @Test
        fun `returns 0 when no sessions to rebind`() {
            `when`(setOps.members("router:instance_sessions:old-inst")).thenReturn(null)

            assertEquals(0, service.rebindAllSessions("old-inst", "new-inst"))
        }

        @Test
        fun `returns 0 when empty set`() {
            `when`(setOps.members("router:instance_sessions:old-inst")).thenReturn(emptySet())

            assertEquals(0, service.rebindAllSessions("old-inst", "new-inst"))
        }

        @Test
        fun `rebinds sessions to new instance and cleans up old`() {
            `when`(setOps.members("router:instance_sessions:old-inst"))
                .thenReturn(setOf("s1", "s2"))

            val count = service.rebindAllSessions("old-inst", "new-inst")

            assertEquals(2, count)
            verify(valueOps).set(eq("router:session:s1"), eq("new-inst"), any<Duration>())
            verify(valueOps).set(eq("router:session:s2"), eq("new-inst"), any<Duration>())
            verify(setOps as SetOperations<String, Any>).add("router:instance_sessions:new-inst", "s1")
            verify(setOps as SetOperations<String, Any>).add("router:instance_sessions:new-inst", "s2")
            verify(redisTemplate).delete("router:instance_sessions:old-inst")
        }
    }

    // ==================== unbindInstanceSessions ====================

    @Nested
    inner class UnbindInstanceSessions {
        @Test
        fun `returns 0 when no sessions`() {
            `when`(setOps.members("router:instance_sessions:inst-1")).thenReturn(emptySet())

            assertEquals(0, service.unbindInstanceSessions("inst-1"))
        }

        @Test
        fun `deletes all session keys and reverse index`() {
            `when`(setOps.members("router:instance_sessions:inst-1"))
                .thenReturn(setOf("s1", "s2"))

            val count = service.unbindInstanceSessions("inst-1")

            assertEquals(2, count)
            verify(redisTemplate).delete(listOf("router:session:s1", "router:session:s2"))
            verify(redisTemplate).delete("router:instance_sessions:inst-1")
        }
    }

    // ==================== getSessionCountByInstance ====================

    @Nested
    inner class GetSessionCountByInstance {
        @Test
        fun `returns 0 when no set exists`() {
            `when`(setOps.size("router:instance_sessions:inst-1")).thenReturn(null)
            assertEquals(0, service.getSessionCountByInstance("inst-1"))
        }

        @Test
        fun `returns set size`() {
            `when`(setOps.size("router:instance_sessions:inst-1")).thenReturn(5L)
            assertEquals(5, service.getSessionCountByInstance("inst-1"))
        }
    }

    // ==================== getSessionCountsByInstances ====================

    @Nested
    inner class GetSessionCountsByInstances {
        @Test
        fun `returns map of counts for all instances`() {
            `when`(setOps.size("router:instance_sessions:inst-1")).thenReturn(3L)
            `when`(setOps.size("router:instance_sessions:inst-2")).thenReturn(7L)

            val counts = service.getSessionCountsByInstances(listOf("inst-1", "inst-2"))

            assertEquals(3, counts["inst-1"])
            assertEquals(7, counts["inst-2"])
        }

        @Test
        fun `returns empty map for empty input`() {
            val counts = service.getSessionCountsByInstances(emptyList())
            assertTrue(counts.isEmpty())
        }
    }
}
