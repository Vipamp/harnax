package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.Duration
import java.time.Instant

class LocalSessionMappingServiceTest {

    private lateinit var instanceRegistry: InstanceRegistry
    private lateinit var service: LocalSessionMappingService

    @BeforeEach
    fun setUp() {
        instanceRegistry = mock(InstanceRegistry::class.java)
        service = LocalSessionMappingService(instanceRegistry, 30000)
    }

    @Test
    fun `bindSession stores mapping and can be retrieved`() {
        service.bindSession("session-1", "inst-1", 42L)

        assertEquals("inst-1", service.getInstanceId("session-1"))
    }

    @Test
    fun `bindSession with null agentId`() {
        service.bindSession("session-1", "inst-1")

        assertEquals("inst-1", service.getInstanceId("session-1"))
    }

    @Test
    fun `bindSession rejects blank sessionId`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.bindSession("", "inst-1")
        }
    }

    @Test
    fun `bindSession rejects blank instanceId`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.bindSession("session-1", "")
        }
    }

    @Test
    fun `getInstanceId returns null when no mapping`() {
        assertNull(service.getInstanceId("session-1"))
    }

    @Test
    fun `unbindSession removes mapping`() {
        service.bindSession("session-1", "inst-1")
        service.unbindSession("session-1")

        assertNull(service.getInstanceId("session-1"))
    }

    @Test
    fun `unbindSession also removes from reverse index`() {
        service.bindSession("session-1", "inst-1")
        service.bindSession("session-2", "inst-1")

        assertEquals(2, service.getSessionCountByInstance("inst-1"))

        service.unbindSession("session-1")

        assertEquals(1, service.getSessionCountByInstance("inst-1"))
    }

    @Test
    fun `refreshActiveTime updates lastActiveTime`() {
        service.bindSession("session-1", "inst-1")

        assertDoesNotThrow { service.refreshActiveTime("session-1") }
    }

    @Test
    fun `refreshActiveTime does nothing for unknown session`() {
        assertDoesNotThrow { service.refreshActiveTime("unknown") }
    }

    @Test
    fun `rerouteSession throws when no healthy instances`() {
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(emptyList())

        assertThrows(IllegalStateException::class.java) {
            service.rerouteSession("session-1")
        }
    }

    @Test
    fun `rerouteSession selects instance and binds`() {
        val inst1 = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
            lastHeartbeat = Instant.now()
        }
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(listOf(inst1))

        val result = service.rerouteSession("session-1")

        assertEquals("inst-1", result)
        assertEquals("inst-1", service.getInstanceId("session-1"))
    }

    @Test
    fun `rebindAllSessions moves sessions between instances`() {
        service.bindSession("session-1", "inst-old")
        service.bindSession("session-2", "inst-old")
        service.bindSession("session-3", "inst-other")

        val count = service.rebindAllSessions("inst-old", "inst-new")

        assertEquals(2, count)
        assertEquals("inst-new", service.getInstanceId("session-1"))
        assertEquals("inst-new", service.getInstanceId("session-2"))
        assertEquals("inst-other", service.getInstanceId("session-3"))
    }

    @Test
    fun `rebindAllSessions returns 0 when no sessions bound to old instance`() {
        val count = service.rebindAllSessions("inst-old", "inst-new")
        assertEquals(0, count)
    }

    @Test
    fun `unbindInstanceSessions removes all sessions for instance`() {
        service.bindSession("session-1", "inst-1")
        service.bindSession("session-2", "inst-1")
        service.bindSession("session-3", "inst-2")

        val count = service.unbindInstanceSessions("inst-1")

        assertEquals(2, count)
        assertNull(service.getInstanceId("session-1"))
        assertNull(service.getInstanceId("session-2"))
        assertEquals("inst-2", service.getInstanceId("session-3"))
    }

    @Test
    fun `unbindInstanceSessions returns 0 when no sessions bound`() {
        val count = service.unbindInstanceSessions("inst-unknown")
        assertEquals(0, count)
    }

    @Test
    fun `getSessionCountByInstance returns correct count`() {
        service.bindSession("session-1", "inst-1")
        service.bindSession("session-2", "inst-1")
        service.bindSession("session-3", "inst-2")

        assertEquals(2, service.getSessionCountByInstance("inst-1"))
        assertEquals(1, service.getSessionCountByInstance("inst-2"))
        assertEquals(0, service.getSessionCountByInstance("inst-3"))
    }

    @Test
    fun `getSessionCountsByInstances returns map of counts`() {
        service.bindSession("session-1", "inst-1")
        service.bindSession("session-2", "inst-1")
        service.bindSession("session-3", "inst-2")

        val counts = service.getSessionCountsByInstances(listOf("inst-1", "inst-2", "inst-3"))

        assertEquals(2, counts["inst-1"])
        assertEquals(1, counts["inst-2"])
        assertEquals(0, counts["inst-3"])
    }

    @Test
    fun `bindSession overwrites previous binding`() {
        service.bindSession("session-1", "inst-1")
        service.bindSession("session-1", "inst-2")

        assertEquals("inst-2", service.getInstanceId("session-1"))
        assertEquals(0, service.getSessionCountByInstance("inst-1"))
        assertEquals(1, service.getSessionCountByInstance("inst-2"))
    }

    // ==================== placement semantics, shared with the Redis mode ====================

    @Test
    fun `rerouteSession never places a session on an excluded instance`() {
        stubHealthy("inst-1", "inst-2")

        val results = (1..20).map { service.rerouteSession("session-$it", setOf("inst-1")) }.toSet()

        assertEquals(setOf("inst-2"), results)
    }

    @Test
    fun `rerouteSession keeps an existing binding instead of load balancing away`() {
        stubHealthy("inst-1", "inst-2")
        repeat(5) { service.bindSession("other-$it", "inst-1") }
        service.bindSession("session-1", "inst-1")

        assertEquals("inst-1", service.rerouteSession("session-1"))
    }

    @Test
    fun `rerouteSession falls back to the bound instance when only it is left`() {
        stubHealthy("inst-1")
        service.bindSession("session-1", "inst-1")

        assertEquals("inst-1", service.rerouteSession("session-1", setOf("inst-1")))
    }

    @Test
    fun `concurrent reroutes of one session leave exactly one index entry`() {
        stubHealthy("inst-1", "inst-2")
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        val threads = (1..8).map { i ->
            Thread {
                runCatching { service.rerouteSession("session-1", setOf("inst-$((i % 2) + 1)")) }
                    .onFailure { failures.add(it) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(emptyList<Throwable>(), failures.toList())
        val bound = service.getInstanceId("session-1")
        assertNotNull(bound)
        val total = service.getSessionCountsByInstances(listOf("inst-1", "inst-2")).values.sum()
        assertEquals(1, total, "only $bound should hold the session")
    }

    @Test
    fun `rebindAllSessions counts only the sessions it moved`() {
        stubHealthy("inst-old", "inst-new")
        service.bindSession("session-1", "inst-old")
        service.bindSession("session-2", "inst-old")
        service.bindSession("session-2", "inst-other")

        val moved = service.rebindAllSessions("inst-old", "inst-new")

        assertEquals(1, moved)
        assertEquals("inst-new", service.getInstanceId("session-1"))
        assertEquals("inst-other", service.getInstanceId("session-2"))
        assertEquals(0, service.getSessionCountByInstance("inst-old"))
        assertEquals(1, service.getSessionCountByInstance("inst-new"))
    }

    @Test
    fun `rebindAllSessions onto the same instance is a no-op`() {
        service.bindSession("session-1", "inst-1")

        assertEquals(0, service.rebindAllSessions("inst-1", "inst-1"))
        assertEquals("inst-1", service.getInstanceId("session-1"))
    }

    @Test
    fun `unbindInstanceSessions leaves sessions that moved away`() {
        service.bindSession("session-1", "inst-1")
        service.bindSession("session-2", "inst-1")
        service.bindSession("session-2", "inst-2")

        val unbound = service.unbindInstanceSessions("inst-1")

        assertEquals(1, unbound)
        assertNull(service.getInstanceId("session-1"))
        assertEquals("inst-2", service.getInstanceId("session-2"))
        assertEquals(0, service.getSessionCountByInstance("inst-1"))
        assertEquals(1, service.getSessionCountByInstance("inst-2"))
    }

    // ==================== Local state has to expire like a Redis binding would ====================

    @Test
    fun `a binding older than the lifetime is dropped along with its reverse index entry`() {
        val expiring = LocalSessionMappingService(instanceRegistry, 30000, Duration.ZERO)
        expiring.bindSession("session-1", "inst-1")
        expiring.bindSession("session-2", "inst-1")
        expiring.unbindSession("session-2")
        expiring.bindSession("session-3", "inst-1")

        assertEquals(2, expiring.getSessionCountByInstance("inst-1"))

        expiring.cleanupStaleState()

        assertNull(expiring.getInstanceId("session-1"))
        assertNull(expiring.getInstanceId("session-3"))
        assertEquals(
            0,
            expiring.getSessionCountByInstance("inst-1"),
            "a dropped binding has to leave the instance's count too",
        )
    }

    @Test
    fun `a binding still inside its lifetime survives the sweep`() {
        stubHealthy("inst-1")
        service.cleanupStaleState()

        service.bindSession("session-1", "inst-1")
        service.cleanupStaleState()

        assertEquals("inst-1", service.getInstanceId("session-1"), "24h of quiet is not a reason to move a session")
        assertEquals(1, service.getSessionCountByInstance("inst-1"))
    }

    private fun stubHealthy(vararg ids: String) {
        val instances = ids.map { id ->
            AgentInstance().apply {
                instanceId = id
                host = "10.0.0.1"
                port = 8080
                status = AgentInstance.STATUS_UP
                active = 1
                lastHeartbeat = Instant.now()
            }
        }
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(instances)
        instances.forEach { `when`(instanceRegistry.getInstance(it.instanceId)).thenReturn(it) }
    }
}
