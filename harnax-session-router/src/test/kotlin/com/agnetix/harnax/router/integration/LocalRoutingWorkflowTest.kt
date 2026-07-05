package com.agnetix.harnax.router.integration

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.health.HeartbeatHealthChecker
import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import com.agnetix.harnax.router.service.impl.CaffeineSessionMappingService
import com.agnetix.harnax.router.service.impl.LocalInstanceCircuitBreaker
import com.agnetix.harnax.router.service.impl.LocalInstanceRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Integration test for the complete local-mode routing workflow.
 * Tests multiple components working together without Spring context.
 */
class LocalRoutingWorkflowTest {

    private lateinit var instanceRegistry: LocalInstanceRegistry
    private lateinit var sessionMappingService: CaffeineSessionMappingService
    private lateinit var circuitBreaker: InstanceCircuitBreaker
    private lateinit var healthChecker: HeartbeatHealthChecker

    @BeforeEach
    fun setUp() {
        instanceRegistry = LocalInstanceRegistry(heartbeatTimeoutMs = 30000)
        sessionMappingService = CaffeineSessionMappingService(instanceRegistry, 30000)
        circuitBreaker = LocalInstanceCircuitBreaker(failureThreshold = 3, openDurationMs = 1000)
        healthChecker = HeartbeatHealthChecker(instanceRegistry, sessionMappingService, 30000)
    }

    private fun createInstance(id: String, host: String = "10.0.0.1", port: Int = 8082): AgentInstance = AgentInstance().apply {
        instanceId = id
        this.host = host
        this.port = port
        status = "UP"
        active = 1
        lastHeartbeat = LocalDateTime.now()
    }

    @Suppress("UNCHECKED_CAST")
    private fun makeInstanceStale(registry: LocalInstanceRegistry, instanceId: String) {
        val field = LocalInstanceRegistry::class.java.getDeclaredField("instances")
        field.isAccessible = true
        val instances = field.get(registry) as ConcurrentHashMap<String, AgentInstance>
        instances[instanceId]?.lastHeartbeat = LocalDateTime.now().minusSeconds(60)
    }

    @Test
    fun `complete workflow - register, bind, route, failover, drain`() {
        // 1. Register two healthy instances
        val inst1 = createInstance("inst-1", "10.0.0.1", 8082)
        val inst2 = createInstance("inst-2", "10.0.0.2", 8082)
        instanceRegistry.registerInstance(inst1.instanceId, inst1.host, inst1.port)
        instanceRegistry.registerInstance(inst2.instanceId, inst2.host, inst2.port)

        assertEquals(2, instanceRegistry.getHealthyInstances().size)

        // 2. Bind sessions to inst-1
        sessionMappingService.bindSession("sess-1", "inst-1")
        sessionMappingService.bindSession("sess-2", "inst-1")
        sessionMappingService.bindSession("sess-3", "inst-2")

        assertEquals("inst-1", sessionMappingService.getInstanceId("sess-1"))
        assertEquals("inst-1", sessionMappingService.getInstanceId("sess-2"))
        assertEquals("inst-2", sessionMappingService.getInstanceId("sess-3"))
        assertEquals(2, sessionMappingService.getSessionCountByInstance("inst-1"))
        assertEquals(1, sessionMappingService.getSessionCountByInstance("inst-2"))

        // 3. Simulate instance-1 going DOWN (stale heartbeat)
        makeInstanceStale(instanceRegistry, "inst-1")

        // 4. Health checker detects and performs failover (rebinds to inst-2)
        healthChecker.checkInstanceHealth()

        // 5. Verify instance-1 is marked DOWN and removed from active instances
        assertNull(instanceRegistry.getInstance("inst-1"))
        val activeInstances = instanceRegistry.getAllActiveInstances()
        assertTrue(activeInstances.none { it.instanceId == "inst-1" })

        // 6. Sessions from inst-1 are now rebound to inst-2
        assertEquals("inst-2", sessionMappingService.getInstanceId("sess-1"))
        assertEquals("inst-2", sessionMappingService.getInstanceId("sess-2"))
        assertEquals("inst-2", sessionMappingService.getInstanceId("sess-3"))
        assertEquals(3, sessionMappingService.getSessionCountByInstance("inst-2"))

        // 7. Drain instance-2 (graceful shutdown)
        instanceRegistry.markAsDraining("inst-2")
        val drainingInst2 = instanceRegistry.getInstance("inst-2")
        assertNotNull(drainingInst2)
        assertEquals("DRAINING", drainingInst2!!.status)

        // 8. Draining instance is not healthy
        val healthyAfterDrain = instanceRegistry.getHealthyInstances()
        assertEquals(0, healthyAfterDrain.size)

        // 9. Unbind all sessions from draining instance
        val unboundCount = sessionMappingService.unbindInstanceSessions("inst-2")
        assertEquals(3, unboundCount)
        assertEquals(0, sessionMappingService.getSessionCountByInstance("inst-2"))
    }

    @Test
    fun `circuit breaker prevents routing to failing instance`() {
        // Register two instances
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        instanceRegistry.registerInstance("inst-2", "10.0.0.2", 8082)

        // Record failures for inst-1 until circuit opens
        circuitBreaker.recordFailure("inst-1")
        circuitBreaker.recordFailure("inst-1")
        assertFalse(circuitBreaker.isOpen("inst-1"))

        circuitBreaker.recordFailure("inst-1")
        assertTrue(circuitBreaker.isOpen("inst-1"))

        // inst-2 should not be affected
        assertFalse(circuitBreaker.isOpen("inst-2"))
    }

    @Test
    fun `heartbeat refresh keeps instance healthy`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)

        // Simulate time passing with heartbeats
        repeat(5) {
            Thread.sleep(100)
            instanceRegistry.refreshHeartbeat("inst-1")
        }

        // Instance should still be healthy
        val healthy = instanceRegistry.getHealthyInstances()
        assertEquals(1, healthy.size)
        assertEquals("inst-1", healthy[0].instanceId)

        // Health check should not mark it down
        healthChecker.checkInstanceHealth()
        val afterCheck = instanceRegistry.getInstance("inst-1")
        assertEquals("UP", afterCheck!!.status)
    }

    @Test
    fun `rebind all sessions from failed instance`() {
        // Register two instances
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        instanceRegistry.registerInstance("inst-2", "10.0.0.2", 8082)

        // Bind multiple sessions to inst-1
        sessionMappingService.bindSession("sess-a", "inst-1")
        sessionMappingService.bindSession("sess-b", "inst-1")
        sessionMappingService.bindSession("sess-c", "inst-1")

        assertEquals(3, sessionMappingService.getSessionCountByInstance("inst-1"))
        assertEquals(0, sessionMappingService.getSessionCountByInstance("inst-2"))

        // Rebind all from inst-1 to inst-2
        val rebindCount = sessionMappingService.rebindAllSessions("inst-1", "inst-2")
        assertEquals(3, rebindCount)

        // Verify all sessions now point to inst-2
        assertEquals("inst-2", sessionMappingService.getInstanceId("sess-a"))
        assertEquals("inst-2", sessionMappingService.getInstanceId("sess-b"))
        assertEquals("inst-2", sessionMappingService.getInstanceId("sess-c"))
        assertEquals(0, sessionMappingService.getSessionCountByInstance("inst-1"))
        assertEquals(3, sessionMappingService.getSessionCountByInstance("inst-2"))
    }

    @Test
    fun `unregister instance cleans up sessions`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        sessionMappingService.bindSession("sess-1", "inst-1")
        sessionMappingService.bindSession("sess-2", "inst-1")

        assertEquals(2, sessionMappingService.getSessionCountByInstance("inst-1"))

        // Unregister instance
        sessionMappingService.unbindInstanceSessions("inst-1")
        instanceRegistry.unregisterInstance("inst-1")

        // Instance should be gone
        assertNull(instanceRegistry.getInstance("inst-1"))
        assertEquals(0, instanceRegistry.getAllActiveInstances().size)

        // Sessions should be unbound
        assertNull(sessionMappingService.getInstanceId("sess-1"))
        assertNull(sessionMappingService.getInstanceId("sess-2"))
    }

    @Test
    fun `load balancing distributes sessions across instances`() {
        // Register 3 instances
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        instanceRegistry.registerInstance("inst-2", "10.0.0.2", 8082)
        instanceRegistry.registerInstance("inst-3", "10.0.0.3", 8082)

        // Reroute 30 sessions (should distribute across instances)
        val sessionCounts = mutableMapOf<String, Int>()
        repeat(30) { i ->
            val sessionId = "sess-$i"
            val instanceId = sessionMappingService.rerouteSession(sessionId)
            sessionCounts[instanceId] = sessionCounts.getOrDefault(instanceId, 0) + 1
        }

        // All 3 instances should have at least 1 session
        assertEquals(3, sessionCounts.size)
        sessionCounts.values.forEach { count ->
            assertTrue(count > 0, "Each instance should have sessions")
            assertTrue(count < 30, "Sessions should be distributed")
        }
    }
}
