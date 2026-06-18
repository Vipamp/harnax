package com.agnetix.harnax.router.health

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.LocalDateTime

class HeartbeatHealthCheckerTest {

    private lateinit var instanceRegistry: InstanceRegistry
    private lateinit var sessionMappingService: SessionMappingService
    private lateinit var checker: HeartbeatHealthChecker

    @BeforeEach
    fun setUp() {
        instanceRegistry = mock(InstanceRegistry::class.java)
        sessionMappingService = mock(SessionMappingService::class.java)
        checker = HeartbeatHealthChecker(
            instanceRegistry,
            sessionMappingService,
            30000,
        )
    }

    private fun healthyInstance(id: String): AgentInstance = AgentInstance().apply {
        instanceId = id
        status = "UP"
        active = 1
        lastHeartbeat = LocalDateTime.now()
    }

    private fun staleInstance(id: String): AgentInstance = AgentInstance().apply {
        instanceId = id
        status = "UP"
        active = 1
        lastHeartbeat = LocalDateTime.now().minusSeconds(60)
    }

    @Test
    fun `checkInstanceHealth does nothing when all instances are healthy`() {
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthyInstance("inst-1")))

        checker.checkInstanceHealth()

        verify(instanceRegistry, never()).markInstanceDown(anyString())
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth marks down instance and rebinds sessions`() {
        val healthy = healthyInstance("inst-1")
        val down = staleInstance("inst-2")
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy, down))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(1)
        `when`(sessionMappingService.rebindAllSessions("inst-2", "inst-1")).thenReturn(3)
        `when`(sessionMappingService.getSessionCountsByInstances(anyList())).thenReturn(mapOf("inst-1" to 5))

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
        verify(sessionMappingService).rebindAllSessions("inst-2", "inst-1")
    }

    @Test
    fun `checkInstanceHealth skips failover when markInstanceDown returns 0`() {
        val healthy = healthyInstance("inst-1")
        val down = staleInstance("inst-2")
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy, down))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(0)

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth does not rebind when no healthy instances available`() {
        val down = staleInstance("inst-1")
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(down))
        `when`(instanceRegistry.markInstanceDown("inst-1")).thenReturn(1)

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-1")
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth does nothing when no active instances`() {
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(emptyList())

        checker.checkInstanceHealth()

        verify(instanceRegistry, never()).markInstanceDown(anyString())
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth detects DRAINING instance as unhealthy`() {
        val healthy = healthyInstance("inst-1")
        val draining = AgentInstance().apply {
            instanceId = "inst-2"
            status = "DRAINING"
            active = 1
            lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy, draining))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(1)
        `when`(sessionMappingService.rebindAllSessions("inst-2", "inst-1")).thenReturn(0)
        `when`(sessionMappingService.getSessionCountsByInstances(anyList())).thenReturn(mapOf("inst-1" to 3))

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
    }

    @Test
    fun `checkInstanceHealth handles multiple down instances`() {
        val healthy = healthyInstance("inst-1")
        val down1 = staleInstance("inst-2")
        val down2 = staleInstance("inst-3")
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy, down1, down2))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(1)
        `when`(instanceRegistry.markInstanceDown("inst-3")).thenReturn(1)
        `when`(sessionMappingService.rebindAllSessions(anyString(), eq("inst-1"))).thenReturn(2)
        `when`(sessionMappingService.getSessionCountsByInstances(anyList())).thenReturn(mapOf("inst-1" to 5))

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
        verify(instanceRegistry).markInstanceDown("inst-3")
        verify(sessionMappingService, times(2)).rebindAllSessions(anyString(), eq("inst-1"))
    }
}
