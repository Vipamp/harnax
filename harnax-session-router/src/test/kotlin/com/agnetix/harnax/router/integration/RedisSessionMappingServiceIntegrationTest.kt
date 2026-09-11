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

    // ==================== reverse index encoding ====================

    @Test
    fun `the reverse index holds the session id in the form the router reads back`() {
        // Every script and every monitor screen compares stored members against plain ids; if the
        // value serializer wrapped them differently these tests would still pass on counts alone.
        sessionMapping.bindSession("sess-1", "inst-1")

        assertEquals(true, indexMember("inst-1", "sess-1"))
        assertEquals(setOf("sess-1"), indexMembers("inst-1"))
    }

    @Test
    fun `re-binding elsewhere removes the session from the old index`() {
        sessionMapping.bindSession("sess-1", "inst-1")
        sessionMapping.bindSession("sess-1", "inst-2")

        assertEquals(false, indexMember("inst-1", "sess-1"))
        assertEquals(true, indexMember("inst-2", "sess-1"))
    }

    // ==================== reroute ====================

    @Test
    fun `rerouteSession never places a session on an excluded instance`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        instanceRegistry.registerInstance("inst-2", "10.0.0.2", 8083)

        val results = (1..30).map { sessionMapping.rerouteSession("sess-$it", setOf("inst-1")) }.toSet()

        assertEquals(setOf("inst-2"), results)
    }

    @Test
    fun `rerouteSession keeps an existing binding instead of load balancing away`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        instanceRegistry.registerInstance("inst-2", "10.0.0.2", 8083)
        // inst-2 is empty and inst-1 is full: sticky routing wins, otherwise every reroute call
        // (failover, retry) would drift the session to wherever traffic currently is lightest.
        repeat(5) { sessionMapping.bindSession("other-$it", "inst-1") }
        sessionMapping.bindSession("sess-1", "inst-1")

        assertEquals("inst-1", sessionMapping.rerouteSession("sess-1"))
    }

    @Test
    fun `concurrent reroutes of one session agree on a single binding and index entry`() {
        // Two router nodes call reroute for the same session at the same time; whoever writes last
        // must not leave the other node's instance counted as holding the session.
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        instanceRegistry.registerInstance("inst-2", "10.0.0.2", 8083)
        instanceRegistry.registerInstance("inst-3", "10.0.0.3", 8084)
        val nodes = List(4) { RedisSessionMappingService(instanceRegistry, getRedisTemplate(), 30000) }
        val placed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        val threads = (1..16).map { i ->
            Thread {
                runCatching { nodes[i % nodes.size].rerouteSession("sess-1", setOf("inst-$((i % 3) + 1)")) }
                    .onSuccess { placed.add(it) }
                    .onFailure { failures.add(it) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(emptyList<Throwable>(), failures.toList())
        assertEquals(16, placed.size)
        val bound = sessionMapping.getInstanceId("sess-1")
        assertNotNull(bound, "the session must end up bound")
        val listedOn = listOf("inst-1", "inst-2", "inst-3").filter { indexMember(it, "sess-1") == true }
        assertEquals(listOf(bound), listedOn, "exactly the bound instance may list the session")
    }

    // ==================== rebind / unbind ====================

    @Test
    fun `rebindAllSessions leaves a session that moved elsewhere alone and drops its stale entry`() {
        sessionMapping.bindSession("s1", "inst-1")
        sessionMapping.bindSession("s2", "inst-1")
        // A second node re-places s2 while this failover is running.
        sessionMapping.bindSession("s2", "inst-3")
        indexAdd("inst-1", "s2")

        val moved = sessionMapping.rebindAllSessions("inst-1", "inst-2")

        assertEquals(1, moved)
        assertEquals("inst-2", sessionMapping.getInstanceId("s1"))
        assertEquals("inst-3", sessionMapping.getInstanceId("s2"))
        assertEquals(0, sessionMapping.getSessionCountByInstance("inst-1"))
        assertEquals(setOf("s2"), indexMembers("inst-3"))
    }

    @Test
    fun `rebindAllSessions drains more sessions than one batch`() {
        val sessions = (1..600).map { "sess-$it" }
        sessions.forEach { sessionMapping.bindSession(it, "inst-1") }

        val moved = sessionMapping.rebindAllSessions("inst-1", "inst-2")

        assertEquals(600, moved)
        assertEquals(0, sessionMapping.getSessionCountByInstance("inst-1"))
        assertEquals(600, sessionMapping.getSessionCountByInstance("inst-2"))
        sessions.forEach { assertEquals("inst-2", sessionMapping.getInstanceId(it)) }
    }

    @Test
    fun `rebindAllSessions onto the same instance is a no-op`() {
        sessionMapping.bindSession("s1", "inst-1")

        assertEquals(0, sessionMapping.rebindAllSessions("inst-1", "inst-1"))
        assertEquals("inst-1", sessionMapping.getInstanceId("s1"))
    }

    @Test
    fun `unbindInstanceSessions keeps sessions that were rerouted away`() {
        sessionMapping.bindSession("s1", "inst-1")
        sessionMapping.bindSession("s2", "inst-1")
        sessionMapping.bindSession("s2", "inst-2")
        indexAdd("inst-1", "s2")

        val unbound = sessionMapping.unbindInstanceSessions("inst-1")

        assertEquals(1, unbound)
        assertNull(sessionMapping.getInstanceId("s1"))
        assertEquals("inst-2", sessionMapping.getInstanceId("s2"))
    }

    @Test
    fun `refreshActiveTime does not resurrect an expired binding`() {
        sessionMapping.bindSession("sess-1", "inst-1")
        getRedisTemplate().delete("router:session:sess-1")

        sessionMapping.refreshActiveTime("sess-1")

        assertNull(sessionMapping.getInstanceId("sess-1"))
    }

    // ==================== helpers ====================

    private fun indexMembers(instanceId: String): Set<Any> = getRedisTemplate().opsForSet().members("router:instance_sessions:$instanceId") ?: emptySet()

    private fun indexMember(
        instanceId: String,
        sessionId: String,
    ): Boolean? = getRedisTemplate().opsForSet().isMember("router:instance_sessions:$instanceId", sessionId)

    private fun indexAdd(
        instanceId: String,
        sessionId: String,
    ) {
        getRedisTemplate().opsForSet().add("router:instance_sessions:$instanceId", sessionId)
    }
}
