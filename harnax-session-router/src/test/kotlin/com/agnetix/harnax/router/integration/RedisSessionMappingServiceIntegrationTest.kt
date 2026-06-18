package com.agnetix.harnax.router.integration

import com.agnetix.harnax.router.service.impl.RedisInstanceRegistry
import com.agnetix.harnax.router.service.impl.RedisSessionMappingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RedisSessionMappingServiceIntegrationTest : RedisIntegrationTestBase() {

    private lateinit var instanceRegistry: RedisInstanceRegistry
    private lateinit var sessionMapping: RedisSessionMappingService

    @BeforeEach
    fun setUp() {
        flushRedis()
        instanceRegistry = RedisInstanceRegistry(getRedisTemplate(), heartbeatTimeoutMs = 30000)
        sessionMapping = RedisSessionMappingService(instanceRegistry, getRedisTemplate(), 30000)
    }

    @Test
    fun `bind and retrieve session`() {
        sessionMapping.bindSession("sess-1", "inst-1")

        assertEquals("inst-1", sessionMapping.getInstanceId("sess-1"))
    }

    @Test
    fun `bind with agentId`() {
        sessionMapping.bindSession("sess-1", "inst-1", agentId = 42L)
        assertEquals("inst-1", sessionMapping.getInstanceId("sess-1"))
    }

    @Test
    fun `bind overwrites existing binding`() {
        sessionMapping.bindSession("sess-1", "inst-1")
        sessionMapping.bindSession("sess-1", "inst-2")

        assertEquals("inst-2", sessionMapping.getInstanceId("sess-1"))
        assertEquals(0, sessionMapping.getSessionCountByInstance("inst-1"))
        assertEquals(1, sessionMapping.getSessionCountByInstance("inst-2"))
    }

    @Test
    fun `bind blank sessionId throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            sessionMapping.bindSession("", "inst-1")
        }
    }

    @Test
    fun `bind blank instanceId throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            sessionMapping.bindSession("sess-1", "")
        }
    }

    @Test
    fun `getInstanceId returns null for unknown session`() {
        assertNull(sessionMapping.getInstanceId("nonexistent"))
    }

    @Test
    fun `unbind removes session`() {
        sessionMapping.bindSession("sess-1", "inst-1")
        sessionMapping.unbindSession("sess-1")

        assertNull(sessionMapping.getInstanceId("sess-1"))
        assertEquals(0, sessionMapping.getSessionCountByInstance("inst-1"))
    }

    @Test
    fun `unbind unknown session is safe`() {
        sessionMapping.unbindSession("nonexistent")
    }

    @Test
    fun `session count tracks multiple sessions`() {
        sessionMapping.bindSession("s1", "inst-1")
        sessionMapping.bindSession("s2", "inst-1")
        sessionMapping.bindSession("s3", "inst-1")
        sessionMapping.bindSession("s4", "inst-2")

        assertEquals(3, sessionMapping.getSessionCountByInstance("inst-1"))
        assertEquals(1, sessionMapping.getSessionCountByInstance("inst-2"))
        assertEquals(0, sessionMapping.getSessionCountByInstance("inst-3"))
    }

    @Test
    fun `rerouteSession selects healthy instance and binds`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)

        val result = sessionMapping.rerouteSession("sess-1")
        assertEquals("inst-1", result)
        assertEquals("inst-1", sessionMapping.getInstanceId("sess-1"))
    }

    @Test
    fun `rerouteSession with no healthy instances throws`() {
        assertThrows(IllegalStateException::class.java) {
            sessionMapping.rerouteSession("sess-1")
        }
    }

    @Test
    fun `rerouteSession distributes across multiple instances`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        instanceRegistry.registerInstance("inst-2", "10.0.0.2", 8083)

        val results = (1..20).map { sessionMapping.rerouteSession("sess-$it") }.toSet()
        assertTrue(results.size >= 2, "Should use at least 2 instances")
    }

    @Test
    fun `rebindAllSessions moves all sessions to new instance`() {
        sessionMapping.bindSession("s1", "inst-1")
        sessionMapping.bindSession("s2", "inst-1")
        sessionMapping.bindSession("s3", "inst-1")

        val count = sessionMapping.rebindAllSessions("inst-1", "inst-2")
        assertEquals(3, count)

        assertEquals("inst-2", sessionMapping.getInstanceId("s1"))
        assertEquals("inst-2", sessionMapping.getInstanceId("s2"))
        assertEquals("inst-2", sessionMapping.getInstanceId("s3"))
        assertEquals(0, sessionMapping.getSessionCountByInstance("inst-1"))
        assertEquals(3, sessionMapping.getSessionCountByInstance("inst-2"))
    }

    @Test
    fun `rebindAllSessions with no sessions returns 0`() {
        assertEquals(0, sessionMapping.rebindAllSessions("inst-empty", "inst-2"))
    }

    @Test
    fun `unbindInstanceSessions removes all sessions for instance`() {
        sessionMapping.bindSession("s1", "inst-1")
        sessionMapping.bindSession("s2", "inst-1")
        sessionMapping.bindSession("s3", "inst-2")

        val count = sessionMapping.unbindInstanceSessions("inst-1")
        assertEquals(2, count)

        assertNull(sessionMapping.getInstanceId("s1"))
        assertNull(sessionMapping.getInstanceId("s2"))
        assertEquals("inst-2", sessionMapping.getInstanceId("s3"))
    }

    @Test
    fun `unbindInstanceSessions with no sessions returns 0`() {
        assertEquals(0, sessionMapping.unbindInstanceSessions("inst-empty"))
    }

    @Test
    fun `getSessionCountsByInstances returns counts for all`() {
        sessionMapping.bindSession("s1", "inst-1")
        sessionMapping.bindSession("s2", "inst-1")
        sessionMapping.bindSession("s3", "inst-2")

        val counts = sessionMapping.getSessionCountsByInstances(listOf("inst-1", "inst-2", "inst-3"))
        assertEquals(2, counts["inst-1"])
        assertEquals(1, counts["inst-2"])
        assertEquals(0, counts["inst-3"])
    }

    @Test
    fun `refreshActiveTime extends TTL`() {
        sessionMapping.bindSession("sess-1", "inst-1")
        sessionMapping.refreshActiveTime("sess-1")
        assertEquals("inst-1", sessionMapping.getInstanceId("sess-1"))
    }
}
