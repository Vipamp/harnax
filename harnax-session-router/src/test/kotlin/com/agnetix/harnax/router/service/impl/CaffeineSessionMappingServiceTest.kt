package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.LocalDateTime

class CaffeineSessionMappingServiceTest {

    private lateinit var instanceRegistry: InstanceRegistry
    private lateinit var service: CaffeineSessionMappingService

    @BeforeEach
    fun setUp() {
        instanceRegistry = mock(InstanceRegistry::class.java)
        service = CaffeineSessionMappingService(instanceRegistry, 30000)
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
            lastHeartbeat = LocalDateTime.now()
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
}
