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
        checker = HeartbeatHealthChecker(instanceRegistry, sessionMappingService, 30000)
    }

    @Test
    fun `checkInstanceHealth does nothing when all instances are healthy`() {
        val healthy = AgentInstance().apply {
            instanceId = "inst-1"; status = "UP"; active = 1; lastHeartbeat = LocalDateTime.now()
        }
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy))

        checker.checkInstanceHealth()

        verify(instanceRegistry, never()).markInstanceDown(anyString())
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth marks down instance and rebinds sessions`() {
        val healthy = AgentInstance().apply {
            instanceId = "inst-1"; status = "UP"; active = 1; lastHeartbeat = LocalDateTime.now()
        }
        val down = AgentInstance().apply {
            instanceId = "inst-2"; status = "UP"; active = 1; lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy, down))
        `when`(sessionMappingService.rebindAllSessions("inst-2", "inst-1")).thenReturn(3)

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
        verify(sessionMappingService).rebindAllSessions("inst-2", "inst-1")
    }

    @Test
    fun `checkInstanceHealth handles multiple down instances`() {
        val healthy = AgentInstance().apply {
            instanceId = "inst-1"; status = "UP"; active = 1; lastHeartbeat = LocalDateTime.now()
        }
        val down1 = AgentInstance().apply {
            instanceId = "inst-2"; status = "UP"; active = 1; lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        val down2 = AgentInstance().apply {
            instanceId = "inst-3"; status = "DOWN"; active = 1; lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy, down1, down2))

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
        verify(instanceRegistry).markInstanceDown("inst-3")
        verify(sessionMappingService, times(2)).rebindAllSessions(anyString(), eq("inst-1"))
    }

    @Test
    fun `checkInstanceHealth does not rebind when no healthy instances available`() {
        val down = AgentInstance().apply {
            instanceId = "inst-1"; status = "UP"; active = 1; lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(down))

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
}
