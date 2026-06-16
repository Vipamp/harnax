package com.agnetix.harnax.router.health

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.mapper.AgentInstanceMapper
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.LocalDateTime

class HeartbeatHealthCheckerTest {

    private lateinit var instanceRegistry: InstanceRegistry
    private lateinit var sessionMappingService: SessionMappingService
    private lateinit var agentInstanceMapper: AgentInstanceMapper
    private lateinit var checker: HeartbeatHealthChecker

    @BeforeEach
    fun setUp() {
        instanceRegistry = mock(InstanceRegistry::class.java)
        sessionMappingService = mock(SessionMappingService::class.java)
        agentInstanceMapper = mock(AgentInstanceMapper::class.java)
        checker = HeartbeatHealthChecker(
            instanceRegistry,
            sessionMappingService,
            agentInstanceMapper,
            30000,
            7,
        )
    }

    // ==================== checkInstanceHealth ====================

    @Test
    fun `checkInstanceHealth does nothing when all instances are healthy`() {
        val healthy = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now()
        }
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy))

        checker.checkInstanceHealth()

        verify(instanceRegistry, never()).markInstanceDown(anyString())
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth marks down instance and rebinds sessions`() {
        val healthy = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now()
        }
        val down = AgentInstance().apply {
            instanceId = "inst-2"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy, down))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(1)
        `when`(sessionMappingService.rebindAllSessions("inst-2", "inst-1")).thenReturn(3)

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
        verify(sessionMappingService).rebindAllSessions("inst-2", "inst-1")
    }

    @Test
    fun `checkInstanceHealth skips failover when markInstanceDown returns 0`() {
        val healthy = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now()
        }
        val down = AgentInstance().apply {
            instanceId = "inst-2"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy, down))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(0)

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth handles multiple down instances`() {
        val healthy = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now()
        }
        val down1 = AgentInstance().apply {
            instanceId = "inst-2"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        val down2 = AgentInstance().apply {
            instanceId = "inst-3"
            status = "DRAINING"
            active = 1
            lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy, down1, down2))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(1)
        `when`(instanceRegistry.markInstanceDown("inst-3")).thenReturn(1)
        `when`(sessionMappingService.rebindAllSessions(anyString(), eq("inst-1"))).thenReturn(2)

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
        verify(instanceRegistry).markInstanceDown("inst-3")
        verify(sessionMappingService, times(2)).rebindAllSessions(anyString(), eq("inst-1"))
    }

    @Test
    fun `checkInstanceHealth does not rebind when no healthy instances available`() {
        val down = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
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
        val healthy = AgentInstance().apply {
            instanceId = "inst-1"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now()
        }
        val draining = AgentInstance().apply {
            instanceId = "inst-2"
            status = "DRAINING"
            active = 1
            lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy, draining))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(1)

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
    }

    @Test
    fun `checkInstanceHealth uses deterministic failover target selection`() {
        val healthy1 = AgentInstance().apply {
            instanceId = "inst-a"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now()
        }
        val healthy2 = AgentInstance().apply {
            instanceId = "inst-b"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now()
        }
        val down = AgentInstance().apply {
            instanceId = "inst-c"
            status = "UP"
            active = 1
            lastHeartbeat = LocalDateTime.now().minusSeconds(60)
        }
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(listOf(healthy1, healthy2, down))
        `when`(instanceRegistry.markInstanceDown("inst-c")).thenReturn(1)
        `when`(sessionMappingService.rebindAllSessions(eq("inst-c"), eq("inst-a"))).thenReturn(5)

        checker.checkInstanceHealth()

        verify(sessionMappingService).rebindAllSessions("inst-c", "inst-a")
    }

    // ==================== cleanupDeletedRecords ====================

    @Test
    fun `cleanupDeletedRecords purges old instances`() {
        `when`(agentInstanceMapper.purgeDeletedInstances(any(LocalDateTime::class.java))).thenReturn(5)

        checker.cleanupDeletedRecords()

        verify(agentInstanceMapper).purgeDeletedInstances(any(LocalDateTime::class.java))
    }

    @Test
    fun `cleanupDeletedRecords handles zero purges`() {
        `when`(agentInstanceMapper.purgeDeletedInstances(any(LocalDateTime::class.java))).thenReturn(0)

        checker.cleanupDeletedRecords()

        verify(agentInstanceMapper).purgeDeletedInstances(any(LocalDateTime::class.java))
    }

    @Test
    fun `cleanupDeletedRecords uses retention days cutoff`() {
        `when`(agentInstanceMapper.purgeDeletedInstances(any(LocalDateTime::class.java))).thenReturn(1)

        checker.cleanupDeletedRecords()

        verify(agentInstanceMapper).purgeDeletedInstances(
            argThat { cutoff ->
                val expected = LocalDateTime.now().minusDays(7)
                cutoff.toLocalDate() == expected.toLocalDate()
            },
        )
    }
}
