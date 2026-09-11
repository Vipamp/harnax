package com.agnetix.harnax.router.integration

import com.agnetix.harnax.router.config.RedisConfig
import com.agnetix.harnax.router.service.impl.RedisInstanceRegistry
import com.agnetix.harnax.router.service.impl.RedisSessionMappingService
import com.agnetix.harnax.router.service.impl.SessionIndexReconciler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration

/**
 * The reconciler exists to fix state that only diverges in Redis, so it is tested against a real one:
 * the divergences are created by writing keys directly, the way a second router node mid-crash would.
 */
class SessionIndexReconcilerIntegrationTest : RedisIntegrationTestBase() {

    private lateinit var instanceRegistry: RedisInstanceRegistry
    private lateinit var sessionMapping: RedisSessionMappingService
    private lateinit var reconciler: SessionIndexReconciler

    @BeforeEach
    fun setUp() {
        flushRedis()
        instanceRegistry = RedisInstanceRegistry(getRedisTemplate(), heartbeatTimeoutMs = 30000)
        sessionMapping = RedisSessionMappingService(instanceRegistry, getRedisTemplate(), 30000)
        reconciler = SessionIndexReconciler(getRedisTemplate(), sessionTtl = Duration.ofMinutes(30))
    }

    private fun indexSize(instanceId: String): Long = getRedisTemplate().opsForSet().size("router:instance_sessions:$instanceId") ?: 0L

    private fun indexMembers(instanceId: String): Set<Any> = getRedisTemplate().opsForSet().members("router:instance_sessions:$instanceId") ?: emptySet()

    /** Writes a binding without touching the index, i.e. exactly the damage a half-finished node makes. */
    private fun bindDirectly(sessionId: String, instanceId: String) {
        getRedisTemplate().opsForValue().set("router:session:$sessionId", instanceId, Duration.ofMinutes(30))
    }

    private fun indexAdd(instanceId: String, sessionId: String) {
        getRedisTemplate().opsForSet().add("router:instance_sessions:$instanceId", sessionId)
    }

    @Test
    fun `drops index entries whose session is gone`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        indexAdd("inst-1", "expired")
        indexAdd("inst-1", "alive")
        bindDirectly("alive", "inst-1")

        reconciler.reconcile()

        assertEquals(setOf("alive"), indexMembers("inst-1"))
    }

    @Test
    fun `moves an entry to the instance the session is actually bound to`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        instanceRegistry.registerInstance("inst-2", "10.0.0.2", 8083)
        bindDirectly("sess-1", "inst-2")
        indexAdd("inst-1", "sess-1")

        reconciler.reconcile()

        assertEquals(0L, indexSize("inst-1"))
        assertEquals(setOf("sess-1"), indexMembers("inst-2"))
    }

    @Test
    fun `restores a live binding that no index lists`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        bindDirectly("sess-1", "inst-1")
        bindDirectly("sess-2", "inst-1")

        reconciler.reconcile()

        // SCARD is what placement reads, so this also proves the restored members are written in the
        // same encoding the rest of the router uses.
        assertEquals(2L, sessionMapping.getSessionCountByInstance("inst-1").toLong())
        assertEquals(setOf("sess-1", "sess-2"), indexMembers("inst-1"))
    }

    @Test
    fun `a restored index expires on its own`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        bindDirectly("sess-1", "inst-1")

        reconciler.reconcile()

        val ttlSeconds = getRedisTemplate().getExpire("router:instance_sessions:inst-1")
        assertNotNull(ttlSeconds)
        assertTrue(ttlSeconds!! > 0, "restored index should carry a TTL, got $ttlSeconds seconds")
    }

    @Test
    fun `deletes the index of an instance that unregistered`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        sessionMapping.bindSession("sess-1", "inst-1")
        instanceRegistry.unregisterInstance("inst-1")

        reconciler.reconcile()

        assertEquals(0L, indexSize("inst-1"))
    }

    @Test
    fun `does not index a binding whose instance is gone`() {
        // Otherwise prune would delete the orphan index and restore would recreate it every round.
        bindDirectly("sess-1", "ghost")

        reconciler.reconcile()

        assertEquals(0L, indexSize("ghost"))
    }

    @Test
    fun `running twice changes nothing`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        instanceRegistry.registerInstance("inst-2", "10.0.0.2", 8083)
        sessionMapping.bindSession("sess-1", "inst-1")
        sessionMapping.bindSession("sess-2", "inst-2")
        indexAdd("inst-1", "ghost-session")
        bindDirectly("unindexed", "inst-2")

        reconciler.reconcile()
        val after = mapOf(
            "inst-1" to indexMembers("inst-1"),
            "inst-2" to indexMembers("inst-2"),
        )

        reconciler.reconcile()

        assertEquals(after, mapOf("inst-1" to indexMembers("inst-1"), "inst-2" to indexMembers("inst-2")))
    }

    @Test
    fun `another node's lock keeps this node out`() {
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        indexAdd("inst-1", "expired")
        getRedisTemplate().opsForValue().set("router:lock:index_reconcile", "other-node", Duration.ofMinutes(5))

        reconciler.reconcile()

        assertEquals(setOf("expired"), indexMembers("inst-1"))
    }

    @Test
    fun `a batch larger than the configured size is fully reconciled`() {
        val smallBatch = SessionIndexReconciler(getRedisTemplate(), sessionTtl = Duration.ofMinutes(30), batchSize = 2)
        instanceRegistry.registerInstance("inst-1", "10.0.0.1", 8082)
        val sessions = (1..5).map { "sess-$it" }
        sessions.forEach { bindDirectly(it, "inst-1") }
        indexAdd("inst-1", "gone-1")
        indexAdd("inst-1", "gone-2")

        smallBatch.reconcile()

        assertEquals(sessions.toSet(), indexMembers("inst-1"))
    }

    @Test
    fun `survives Redis being unreachable`() {
        // A scheduled task that lets a Redis outage escape must not take routing decisions down with
        // it, and it must not lose the lock it never got.
        val factory = LettuceConnectionFactory(RedisStandaloneConfiguration("127.0.0.1", 1)).apply { afterPropertiesSet() }
        val unreachableTemplate: RedisTemplate<String, Any> = RedisConfig().redisTemplate(factory)
        try {
            val broken = SessionIndexReconciler(unreachableTemplate)
            assertDoesNotThrow { broken.reconcile() }
        } finally {
            factory.destroy()
        }
    }
}
