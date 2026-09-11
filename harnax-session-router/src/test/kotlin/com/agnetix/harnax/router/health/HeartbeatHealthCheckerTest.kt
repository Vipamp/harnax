package com.agnetix.harnax.router.health

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import java.time.Instant

class HeartbeatHealthCheckerTest {

    private lateinit var instanceRegistry: InstanceRegistry
    private lateinit var sessionMappingService: SessionMappingService
    private lateinit var meterRegistry: SimpleMeterRegistry
    private lateinit var checker: HeartbeatHealthChecker

    @BeforeEach
    fun setUp() {
        instanceRegistry = mock(InstanceRegistry::class.java)
        sessionMappingService = mock(SessionMappingService::class.java)
        meterRegistry = SimpleMeterRegistry()
        checker = HeartbeatHealthChecker(
            instanceRegistry,
            sessionMappingService,
            LocalInstanceCircuitBreaker(failureThreshold = 100, openDurationMs = 1),
            meterRegistry,
            HEARTBEAT_TIMEOUT_MS,
        )
    }

    private fun healthyInstance(id: String): AgentInstance = AgentInstance().apply {
        instanceId = id
        status = "UP"
        active = 1
        lastHeartbeat = Instant.now()
    }

    private fun staleInstance(id: String): AgentInstance = AgentInstance().apply {
        instanceId = id
        status = "UP"
        active = 1
        lastHeartbeat = Instant.now().minusSeconds(60)
    }

    private fun drainingInstance(id: String): AgentInstance = AgentInstance().apply {
        instanceId = id
        status = "DRAINING"
        active = 1
        lastHeartbeat = Instant.now().minusSeconds(60)
    }

    /**
     * Stubs the registry the way both implementations behave: `all active` reports every live
     * registration, while failover candidates come from `getHealthyInstances()`.
     */
    private fun stubInstances(vararg instances: AgentInstance) {
        val all = instances.toList()
        `when`(instanceRegistry.getAllActiveInstances()).thenReturn(all)
        `when`(instanceRegistry.getHealthyInstances())
            .thenReturn(all.filter { it.isAcceptingNewSessions(HEARTBEAT_TIMEOUT_MS) })
    }

    @Test
    fun `checkInstanceHealth does nothing when all instances are healthy`() {
        stubInstances(healthyInstance("inst-1"))

        checker.checkInstanceHealth()

        verify(instanceRegistry, never()).markInstanceDown(anyString())
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth marks down instance and rebinds sessions`() {
        stubInstances(healthyInstance("inst-1"), staleInstance("inst-2"))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(1)
        `when`(sessionMappingService.rebindAllSessions("inst-2", "inst-1")).thenReturn(3)
        `when`(sessionMappingService.getSessionCountsByInstances(any<List<String>>())).thenReturn(mapOf("inst-1" to 5))

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
        verify(sessionMappingService).rebindAllSessions("inst-2", "inst-1")
    }

    @Test
    fun `checkInstanceHealth skips failover when markInstanceDown returns 0`() {
        stubInstances(healthyInstance("inst-1"), staleInstance("inst-2"))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(0)

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth does not rebind when no healthy instances available`() {
        stubInstances(staleInstance("inst-1"))
        `when`(instanceRegistry.markInstanceDown("inst-1")).thenReturn(1)

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-1")
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth does nothing when no active instances`() {
        stubInstances()

        checker.checkInstanceHealth()

        verify(instanceRegistry, never()).markInstanceDown(anyString())
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth detects a stale DRAINING instance as down`() {
        stubInstances(healthyInstance("inst-1"), drainingInstance("inst-2"))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(1)
        `when`(sessionMappingService.rebindAllSessions("inst-2", "inst-1")).thenReturn(0)
        `when`(sessionMappingService.getSessionCountsByInstances(any<List<String>>())).thenReturn(mapOf("inst-1" to 3))

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
    }

    @Test
    fun `checkInstanceHealth leaves a live draining instance alone`() {
        // DRAINING means "finish what you have, take nothing new". Treating it as crashed would
        // turn every graceful shutdown into a failover storm and log the instance as DOWN.
        val draining = AgentInstance().apply {
            instanceId = "inst-2"
            status = "DRAINING"
            active = 1
            lastHeartbeat = Instant.now()
        }
        stubInstances(healthyInstance("inst-1"), draining)

        checker.checkInstanceHealth()

        verify(instanceRegistry, never()).markInstanceDown(anyString())
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth never migrates sessions onto a draining instance`() {
        val freshDraining = AgentInstance().apply {
            instanceId = "inst-3"
            status = "DRAINING"
            active = 1
            lastHeartbeat = Instant.now()
        }
        stubInstances(freshDraining, staleInstance("inst-2"))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(1)
        `when`(sessionMappingService.getSessionCountsByInstances(any<List<String>>()))
            .thenReturn(mapOf("inst-3" to 0))

        checker.checkInstanceHealth()

        // inst-3 is the only live registration, but it is draining, so there is no valid target.
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `checkInstanceHealth handles multiple down instances`() {
        stubInstances(healthyInstance("inst-1"), staleInstance("inst-2"), staleInstance("inst-3"))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(1)
        `when`(instanceRegistry.markInstanceDown("inst-3")).thenReturn(1)
        `when`(sessionMappingService.rebindAllSessions(anyString(), eq("inst-1"))).thenReturn(2)
        `when`(sessionMappingService.getSessionCountsByInstances(any<List<String>>())).thenReturn(mapOf("inst-1" to 5))

        checker.checkInstanceHealth()

        verify(instanceRegistry).markInstanceDown("inst-2")
        verify(instanceRegistry).markInstanceDown("inst-3")
        verify(sessionMappingService, times(2)).rebindAllSessions(anyString(), eq("inst-1"))
    }

    @Test
    fun `checkInstanceHealth respects failover cooldown — second call skips recently handled instance`() {
        stubInstances(healthyInstance("inst-1"), staleInstance("inst-2"))
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(1)
        `when`(sessionMappingService.rebindAllSessions("inst-2", "inst-1")).thenReturn(2)
        `when`(sessionMappingService.getSessionCountsByInstances(any<List<String>>())).thenReturn(mapOf("inst-1" to 3))

        checker.checkInstanceHealth()

        verify(instanceRegistry, times(1)).markInstanceDown("inst-2")

        checker.checkInstanceHealth()

        verify(instanceRegistry, times(1)).markInstanceDown("inst-2")
    }

    @Test
    fun `checkInstanceHealth selects less loaded alternative when primary target is overloaded`() {
        stubInstances(
            healthyInstance("inst-1"),
            healthyInstance("inst-3"),
            healthyInstance("inst-4"),
            staleInstance("inst-2"),
        )
        `when`(instanceRegistry.markInstanceDown("inst-2")).thenReturn(1)
        `when`(sessionMappingService.getSessionCountsByInstances(any<List<String>>())).thenReturn(
            mapOf("inst-1" to 30, "inst-3" to 3, "inst-4" to 5),
        )
        `when`(sessionMappingService.rebindAllSessions(eq("inst-2"), anyString())).thenReturn(2)

        checker.checkInstanceHealth()

        verify(sessionMappingService).rebindAllSessions("inst-2", "inst-3")
    }

    @Test
    fun `checkInstanceHealth handles no healthy instances without throwing`() {
        stubInstances(staleInstance("inst-1"))
        `when`(instanceRegistry.markInstanceDown("inst-1")).thenReturn(1)

        assertDoesNotThrow { checker.checkInstanceHealth() }

        verify(instanceRegistry).markInstanceDown("inst-1")
        verify(sessionMappingService, never()).rebindAllSessions(anyString(), anyString())
    }

    @Test
    fun `the fleet gauge counts instances this router can actually place a session on`() {
        stubInstances(
            healthyInstance("inst-1"),
            healthyInstance("inst-3"),
            drainingInstance("inst-2"),
            staleInstance("inst-4"),
        )
        checker.registerGauges()

        val gauge = meterRegistry.get("router.healthy.instances").gauge()
        assertEquals(2.0, gauge.value())

        // Read at scrape time, so a fleet that shrinks between scrapes is reflected without a restart.
        stubInstances(healthyInstance("inst-1"))
        assertEquals(1.0, gauge.value())
    }

    companion object {
        private const val HEARTBEAT_TIMEOUT_MS = 30000L
    }
}
