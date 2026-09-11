package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import java.time.Instant

/**
 * Kotlin-side branches of [RedisSessionMappingService]: argument validation, the placement decision
 * and the guard rails around the Lua calls. The scripts themselves are covered against a real Redis
 * in [com.agnetix.harnax.router.integration.RedisSessionMappingServiceIntegrationTest] — a mocked
 * [RedisTemplate] cannot observe what a script does to the stored keys, so anything that claims or
 * moves a binding belongs there.
 */
class RedisSessionMappingServiceTest {

    @Suppress("UNCHECKED_CAST")
    private val redisTemplate: RedisTemplate<String, Any> = mock(RedisTemplate::class.java, RETURNS_DEEP_STUBS) as RedisTemplate<String, Any>
    private val instanceRegistry: InstanceRegistry = mock(InstanceRegistry::class.java)

    private lateinit var service: RedisSessionMappingService

    @BeforeEach
    fun setUp() {
        service = RedisSessionMappingService(instanceRegistry, redisTemplate, heartbeatTimeoutMs = 30000)
    }

    private fun instance(id: String): AgentInstance = AgentInstance().apply {
        instanceId = id
        host = "10.0.0.1"
        port = 8080
        status = AgentInstance.STATUS_UP
        active = 1
        lastHeartbeat = Instant.now()
    }

    private fun bindExisting(sessionId: String, instanceId: String) {
        `when`(redisTemplate.opsForValue().get("router:session:$sessionId")).thenReturn(instanceId)
    }

    @Test
    fun `every entry point rejects a blank or oversized session id`() {
        val tooLong = "s".repeat(129)
        for (id in listOf("", "  ", tooLong)) {
            assertThrows(IllegalArgumentException::class.java) { service.bindSession(id, "inst-1") }
            assertThrows(IllegalArgumentException::class.java) { service.getInstanceId(id) }
            assertThrows(IllegalArgumentException::class.java) { service.unbindSession(id) }
            assertThrows(IllegalArgumentException::class.java) { service.refreshActiveTime(id) }
            assertThrows(IllegalArgumentException::class.java) { service.rerouteSession(id) }
        }
    }

    @Test
    fun `bindSession rejects a blank or oversized instance id`() {
        assertThrows(IllegalArgumentException::class.java) { service.bindSession("session-1", "") }
        assertThrows(IllegalArgumentException::class.java) { service.bindSession("session-1", "i".repeat(65)) }
    }

    @Test
    fun `rerouteSession throws when nothing is registered`() {
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(emptyList())

        val error = assertThrows(IllegalStateException::class.java) { service.rerouteSession("session-1") }
        assertTrue(error.message!!.contains("No healthy"), "unexpected message: ${error.message}")
    }

    @Test
    fun `rerouteSession keeps a binding whose instance is still eligible`() {
        bindExisting("session-1", "inst-1")
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(listOf(instance("inst-1")))

        assertEquals("inst-1", service.rerouteSession("session-1"))
    }

    @Test
    fun `rerouteSession does not move a binding just because the instance is not listed as healthy`() {
        // A stale heartbeat removes an instance from the candidate list, not from serving sessions it
        // already holds; moving is only worth it when there is somewhere to move to and the current
        // instance is excluded by the caller.
        bindExisting("session-1", "inst-1")
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(listOf(instance("inst-2")))
        `when`(instanceRegistry.getInstance("inst-1")).thenReturn(instance("inst-1"))

        assertEquals("inst-1", service.rerouteSession("session-1", setOf("inst-2")))
    }

    @Test
    fun `rerouteSession gives up when the bound instance is excluded and unusable`() {
        bindExisting("session-1", "inst-1")
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(listOf(instance("inst-1")))

        assertThrows(IllegalStateException::class.java) { service.rerouteSession("session-1", setOf("inst-1")) }
    }

    @Test
    fun `rerouteSession survives a Redis lock failure`() {
        bindExisting("session-1", "inst-1")
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(listOf(instance("inst-1")))
        `when`(redisTemplate.opsForValue().setIfAbsent(any<String>(), any<String>(), any<Duration>()))
            .thenThrow(RuntimeException("Redis is down"))

        assertEquals("inst-1", service.rerouteSession("session-1"))
    }

    @Test
    fun `rebindAllSessions with source equal to target does nothing`() {
        assertEquals(0, service.rebindAllSessions("inst-1", "inst-1"))
    }

    // ==================== outage degradation ====================

    private fun redisStoreIsDown() {
        `when`(redisTemplate.opsForValue().get(any<String>())).thenThrow(RuntimeException("Redis is down"))
    }

    @Test
    fun `getInstanceId serves the binding this node last read while Redis is unreachable`() {
        bindExisting("session-1", "inst-1")
        assertEquals("inst-1", service.getInstanceId("session-1"))

        redisStoreIsDown()

        assertEquals("inst-1", service.getInstanceId("session-1"))
    }

    @Test
    fun `getInstanceId fails a session this node has never seen`() {
        redisStoreIsDown()

        assertThrows(RuntimeException::class.java) { service.getInstanceId("session-new") }
    }

    @Test
    fun `rerouteSession places a session from the degraded fleet even though it cannot persist it`() {
        redisStoreIsDown()
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(listOf(instance("inst-1")))

        assertEquals("inst-1", service.rerouteSession("session-1"))
        // What this node placed is what this node keeps routing on, or one long outage would hand the
        // same session to a different instance on every request.
        assertEquals("inst-1", service.getInstanceId("session-1"))
    }

    @Test
    fun `rerouteSession fails when neither bindings nor instances can be read`() {
        redisStoreIsDown()
        `when`(instanceRegistry.getHealthyInstances()).thenReturn(emptyList())

        assertThrows(IllegalStateException::class.java) { service.rerouteSession("session-1") }
    }

    @Test
    fun `session counts fall back to what this node has placed`() {
        bindExisting("session-1", "inst-1")
        bindExisting("session-2", "inst-1")
        bindExisting("session-3", "inst-2")
        for (sessionId in listOf("session-1", "session-2", "session-3")) {
            service.getInstanceId(sessionId)
        }

        `when`(redisTemplate.opsForSet().size(any<String>())).thenThrow(RuntimeException("Redis is down"))

        assertEquals(
            mapOf("inst-1" to 2, "inst-2" to 1),
            service.getSessionCountsByInstances(listOf("inst-1", "inst-2")),
        )
    }
}
