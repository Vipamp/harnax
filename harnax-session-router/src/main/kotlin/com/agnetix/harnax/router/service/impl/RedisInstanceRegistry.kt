package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure Redis-based distributed instance registry.
 * Redis is the single source of truth - no MySQL dependency.
 *
 * State transitions that other router nodes may race for (heartbeat, down, drain) are single Lua
 * scripts: [com.agnetix.harnax.router.health.HeartbeatHealthChecker] runs on every node with the
 * same schedule, so a read-then-write sequence lets several nodes "win" the same transition and
 * each of them then migrates the same sessions.
 *
 * Values are written through the template's JSON serializer (see [com.agnetix.harnax.router.config.RedisConfig]),
 * so a String field is stored with its quotes and numeric script arguments must be passed as
 * numbers, not strings.
 */
class RedisInstanceRegistry(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val heartbeatTimeoutMs: Long,
) : InstanceRegistry {

    private val log = LoggerFactory.getLogger(RedisInstanceRegistry::class.java)

    /**
     * Last fleet Redis actually reported, used only while Redis is unreachable. Redis remains the
     * source of truth: this snapshot is overwritten by every successful read and it ages on its
     * own, because the heartbeats it carries go stale against [heartbeatTimeoutMs] and the filter
     * in [degradedInstances] then drops every instance — routing then stops instead of routing
     * forever to a fleet that died while Redis was down.
     */
    private val knownInstances = AtomicReference<Map<String, AgentInstance>>(emptyMap())

    private val degrade = ThrottledWarn()

    companion object {
        internal const val INSTANCE_KEY_PREFIX = "router:instance:"
        private const val HEALTHY_SET_KEY = "router:instances:healthy"
        private const val ALL_SET_KEY = "router:instances:all"
        private val INSTANCE_TTL = Duration.ofHours(24)

        // Lua source literals for JSON-encoded status values. The value serializer stores the
        // String "UP" as the four bytes "UP" (quotes included), so scripts must compare and write
        // exactly that.
        private const val JSON_UP = "'\"UP\"'"
        private const val JSON_DOWN = "'\"DOWN\"'"
        private const val JSON_DRAINING = "'\"DRAINING\"'"

        /**
         * KEYS[1]=instance key, KEYS[2]=healthy set
         * ARGV[1]=instanceId (JSON string), ARGV[2]=epoch millis, ARGV[3]=TTL millis
         * @return 1 when refreshed, 0 when the instance is no longer registered.
         *
         * A DRAINING instance stays draining: a heartbeat proves liveness, it must not silently
         * undo an operator's drain. A DOWN instance is recovered, since DOWN is reached only
         * through heartbeat staleness.
         */
        private val HEARTBEAT_SCRIPT = DefaultRedisScript(
            """
            if redis.call('exists', KEYS[1]) == 0 then return 0 end
            redis.call('hset', KEYS[1], 'lastHeartbeat', ARGV[2])
            redis.call('pexpire', KEYS[1], ARGV[3])
            local status = redis.call('hget', KEYS[1], 'status')
            if status ~= $JSON_DRAINING then
                redis.call('hset', KEYS[1], 'status', $JSON_UP)
                redis.call('sadd', KEYS[2], ARGV[1])
            end
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * KEYS[1]=instance key, KEYS[2]=healthy set, ARGV[1]=instanceId (JSON string)
         * @return 1 if this caller performed the transition to DOWN, 0 if already DOWN or missing.
         */
        private val MARK_DOWN_SCRIPT = DefaultRedisScript(
            """
            if redis.call('exists', KEYS[1]) == 0 then return 0 end
            if redis.call('hget', KEYS[1], 'status') == $JSON_DOWN then return 0 end
            redis.call('hset', KEYS[1], 'status', $JSON_DOWN)
            redis.call('srem', KEYS[2], ARGV[1])
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * KEYS[1]=instance key, KEYS[2]=healthy set
         * ARGV[1]=instanceId (JSON string), ARGV[2]=epoch millis
         * @return 1 when the instance exists and is now draining, 0 otherwise.
         * Never creates a key: draining an unknown instance must not leave a half-populated hash
         * behind (host/port would be empty and every request routed to it would fail).
         */
        private val MARK_DRAINING_SCRIPT = DefaultRedisScript(
            """
            if redis.call('exists', KEYS[1]) == 0 then return 0 end
            redis.call('hset', KEYS[1], 'status', $JSON_DRAINING, 'lastHeartbeat', ARGV[2])
            redis.call('srem', KEYS[2], ARGV[1])
            return 1
            """.trimIndent(),
            Long::class.java,
        )
    }

    override fun registerInstance(instanceId: String, host: String, port: Int) {
        val instanceKey = "$INSTANCE_KEY_PREFIX$instanceId"
        val previous = getInstance(instanceId)
        if (previous != null && (previous.host != host || previous.port != port)) {
            log.warn(
                "Instance {} re-registered at {}:{} (was {}:{}); sessions bound to the old address " +
                    "will reroute on their next request",
                instanceId,
                host,
                port,
                previous.host,
                previous.port,
            )
        }

        val instanceData = mapOf(
            "instanceId" to instanceId,
            "host" to host,
            "port" to port,
            "status" to AgentInstance.STATUS_UP,
            "lastHeartbeat" to Instant.now().toEpochMilli(),
            "active" to 1,
        )
        redisTemplate.opsForHash<String, Any>().putAll(instanceKey, instanceData)
        redisTemplate.expire(instanceKey, INSTANCE_TTL)
        redisTemplate.opsForSet().add(HEALTHY_SET_KEY, instanceId)
        redisTemplate.opsForSet().add(ALL_SET_KEY, instanceId)

        log.info("Registered instance: $instanceId at $host:$port")
    }

    override fun unregisterInstance(instanceId: String) {
        val instanceKey = "$INSTANCE_KEY_PREFIX$instanceId"
        redisTemplate.delete(instanceKey)
        redisTemplate.opsForSet().remove(HEALTHY_SET_KEY, instanceId)
        redisTemplate.opsForSet().remove(ALL_SET_KEY, instanceId)
        log.info("Unregistered instance: $instanceId")
    }

    override fun refreshHeartbeat(instanceId: String): Boolean {
        val result = redisTemplate.execute(
            HEARTBEAT_SCRIPT,
            listOf("$INSTANCE_KEY_PREFIX$instanceId", HEALTHY_SET_KEY),
            instanceId,
            Instant.now().toEpochMilli(),
            INSTANCE_TTL.toMillis(),
        )
        if (result != 1L) {
            log.warn("Heartbeat rejected - instance not registered: $instanceId; agent must re-register")
            return false
        }
        log.debug("Refreshed heartbeat for instance: $instanceId")
        return true
    }

    override fun getHealthyInstances(): List<AgentInstance> = try {
        readHealthyInstances().also { instances ->
            // Mirror what Redis actually said, an empty fleet included: a snapshot of instances the
            // registry has already declared unhealthy would route to a dead agent.
            knownInstances.set(instances.associateBy { it.instanceId })
        }
    } catch (e: Exception) {
        degradedInstances(e)
    }

    private fun readHealthyInstances(): List<AgentInstance> {
        val memberIds = redisTemplate.opsForSet().members(HEALTHY_SET_KEY)
        if (memberIds.isNullOrEmpty()) return emptyList()

        val instances = mutableListOf<AgentInstance>()
        val unregistered = mutableListOf<Any>()

        for (id in memberIds) {
            val instance = readInstance(id as String)
            // DRAINING instances are alive but must not receive new sessions.
            when {
                instance == null -> unregistered.add(id)
                instance.isAcceptingNewSessions(heartbeatTimeoutMs) -> instances.add(instance)
            }
        }

        if (unregistered.isNotEmpty()) {
            // Only drop ids whose registration is genuinely gone; a DRAINING or momentarily stale
            // instance must stay in the set so it can come back without re-registering.
            redisTemplate.opsForSet().remove(HEALTHY_SET_KEY, *unregistered.toTypedArray())
            log.info("Removed ${unregistered.size} unregistered instances from the healthy set")
        }

        return instances
    }

    override fun getAllActiveInstances(): List<AgentInstance> {
        val memberIds = redisTemplate.opsForSet().members(ALL_SET_KEY) ?: return emptyList()

        val instances = mutableListOf<AgentInstance>()
        val orphanIds = mutableListOf<Any>()
        for (id in memberIds) {
            // Raw read on purpose: this feeds pruning and the health checker, which must never act
            // on a snapshot the outage left behind.
            val instance = readInstance(id as String)
            if (instance == null) {
                // The hash expired or was deleted while its id stayed in the set; without this the
                // set grows forever and the health checker can never see it to clean it up.
                orphanIds.add(id)
            } else if (instance.active == 1) {
                instances.add(instance)
            }
        }
        if (orphanIds.isNotEmpty()) {
            redisTemplate.opsForSet().remove(ALL_SET_KEY, *orphanIds.toTypedArray())
            redisTemplate.opsForSet().remove(HEALTHY_SET_KEY, *orphanIds.toTypedArray())
            log.info("Pruned ${orphanIds.size} orphaned ids from the instance sets")
        }
        return instances
    }

    override fun getInstance(instanceId: String): AgentInstance? = try {
        readInstance(instanceId)
    } catch (e: Exception) {
        knownInstances.get()[instanceId]
            ?.takeIf { it.isAcceptingNewSessions(heartbeatTimeoutMs) }
            ?.also { degrade.log(log, "Instance registry unreachable, serving instance $instanceId from the last-known registration", e) }
            ?: throw e
    }

    private fun readInstance(instanceId: String): AgentInstance? {
        val hashEntries = redisTemplate.opsForHash<String, Any>().entries("$INSTANCE_KEY_PREFIX$instanceId")
        if (hashEntries.isEmpty()) return null
        return reconstructInstanceFromHash(instanceId, hashEntries)
    }

    private fun degradedInstances(e: Exception): List<AgentInstance> {
        val known = knownInstances.get().values.filter { it.isAcceptingNewSessions(heartbeatTimeoutMs) }
        degrade.log(
            log,
            "Instance registry unreachable, routing on ${known.size} last-known instances " +
                "(their heartbeats age out after ${heartbeatTimeoutMs}ms without Redis)",
            e,
        )
        return known
    }

    override fun markInstanceDown(instanceId: String): Int {
        val result = redisTemplate.execute(
            MARK_DOWN_SCRIPT,
            listOf("$INSTANCE_KEY_PREFIX$instanceId", HEALTHY_SET_KEY),
            instanceId,
        )
        if (result != 1L) return 0
        log.warn("Marked instance as DOWN: $instanceId")
        return 1
    }

    override fun markAsDraining(instanceId: String): Boolean {
        val result = redisTemplate.execute(
            MARK_DRAINING_SCRIPT,
            listOf("$INSTANCE_KEY_PREFIX$instanceId", HEALTHY_SET_KEY),
            instanceId,
            Instant.now().toEpochMilli(),
        )
        if (result != 1L) {
            log.warn("Drain ignored - instance not registered: $instanceId")
            return false
        }
        log.info("Marked instance as DRAINING: $instanceId")
        return true
    }

    private fun reconstructInstanceFromHash(
        instanceId: String,
        hashEntries: Map<String, Any>,
    ): AgentInstance = AgentInstance().apply {
        this.instanceId = instanceId
        this.host = hashEntries["host"] as? String ?: ""
        this.port = (hashEntries["port"] as? Number)?.toInt() ?: 0
        this.status = hashEntries["status"] as? String ?: AgentInstance.STATUS_UP
        this.active = (hashEntries["active"] as? Number)?.toInt() ?: 1
        this.lastHeartbeat = AgentInstance.parseHeartbeat(hashEntries["lastHeartbeat"]?.toString())
            ?: Instant.now()
    }
}
