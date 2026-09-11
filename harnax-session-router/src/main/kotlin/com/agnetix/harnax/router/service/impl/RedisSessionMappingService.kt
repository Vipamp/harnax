package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Pure Redis-based distributed session mapping service.
 * Redis is the single source of truth - no MySQL dependency.
 *
 * Every state change touches at least two keys (the binding and one or both per-instance reverse
 * indexes), and several router nodes run this code concurrently for the same session, so each
 * change is one Lua script. A read-modify-write in Kotlin would let a second node overwrite the
 * first one's binding and leave the indexes pointing at an instance that no longer serves the
 * session.
 *
 * Encoding note: values are written through the JSON serializer configured in
 * [com.agnetix.harnax.router.config.RedisConfig], so a stored String carries its quotes and script
 * arguments that are Strings are serialized the same way. Keys use the plain string serializer,
 * which is why the key prefixes are passed as KEYS entries and concatenated inside Lua.
 *
 * Outage behaviour: Redis is authoritative and stays that way. [lastKnownBindings] is a hint this
 * node accumulates from what Redis told it (and from placements it could not persist), consulted
 * only when Redis cannot be reached. It lets routing continue through a Redis outage instead of
 * failing every request, at the cost of session affinity between nodes for the duration — which is
 * exactly what an outage costs anyway, since no node can read or write bindings.
 */
class RedisSessionMappingService(
    private val instanceRegistry: InstanceRegistry,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val heartbeatTimeoutMs: Long = 30000L,
) : SessionMappingService {

    private val log = LoggerFactory.getLogger(RedisSessionMappingService::class.java)

    /**
     * sessionId -> instanceId as this node last saw it. Bounded: losing an entry only means that
     * node cannot serve that session from the shadow while Redis is down, and 24h matches the
     * binding TTL in Redis, so a shadow entry never outlives the binding it describes.
     */
    private val lastKnownBindings: Cache<String, String> = Caffeine.newBuilder()
        .maximumSize(50_000)
        .expireAfterWrite(SESSION_TTL.toMillis(), TimeUnit.MILLISECONDS)
        .build()

    private val degrade = ThrottledWarn()

    companion object {
        internal const val SESSION_KEY_PREFIX = "router:session:"
        internal const val INDEX_KEY_PREFIX = "router:instance_sessions:"
        private const val LOCK_KEY_PREFIX = "router:lock:session:"
        private const val LOCK_TIMEOUT_SECONDS = 10L

        /**
         * Sessions a single rebind/unbind script call processes. Bounded so one call cannot run
         * longer than a Redis client's patience, while keeping the round trips per instance small.
         */
        private const val REBIND_BATCH_SIZE = 500L

        /**
         * Cap on rebind/unbind batches: a session index that is being written as fast as it is
         * drained must not pin a scheduler thread forever.
         */
        private const val MAX_REBIND_BATCHES = 200

        /** How often reroute re-reads the binding when another node wins the race. */
        private const val REROUTE_ATTEMPTS = 3
        internal val SESSION_TTL = Duration.ofHours(24)
        private val SESSION_TTL_MS = SESSION_TTL.toMillis()

        /**
         * KEYS[1]=session, KEYS[2]=new instance index, KEYS[3]=index key prefix
         * ARGV[1]=instanceId, ARGV[2]=sessionId, ARGV[3]=ttl millis
         */
        private val BIND_SCRIPT = DefaultRedisScript(
            """
            local cur = redis.call('get', KEYS[1])
            if cur and cur ~= ARGV[1] then
                redis.call('srem', KEYS[3] .. string.sub(cur, 2, -2), ARGV[2])
            end
            redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[3])
            redis.call('sadd', KEYS[2], ARGV[2])
            redis.call('pexpire', KEYS[2], ARGV[3])
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * KEYS[1]=session, KEYS[2]=instance index
         * ARGV[1]=sessionId, ARGV[2]=instanceId, ARGV[3]=ttl millis
         * @return 1 if this caller created the binding, 0 if the session is already bound.
         */
        private val CLAIM_SCRIPT = DefaultRedisScript(
            """
            if redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3], 'NX') then
                redis.call('sadd', KEYS[2], ARGV[1])
                redis.call('pexpire', KEYS[2], ARGV[3])
                return 1
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * KEYS[1]=session, KEYS[2]=source index, KEYS[3]=target index
         * ARGV[1]=sessionId, ARGV[2]=source instanceId, ARGV[3]=target instanceId, ARGV[4]=ttl millis
         * @return 1 if the binding still pointed at the source and has been moved, 0 otherwise.
         *
         * The entry is always removed from the source index: if the binding is gone or belongs to
         * another instance, the index entry is stale and would otherwise be counted as load forever.
         */
        private val MOVE_IF_FROM_SCRIPT = DefaultRedisScript(
            """
            local cur = redis.call('get', KEYS[1])
            redis.call('srem', KEYS[2], ARGV[1])
            if cur ~= ARGV[2] then
                return 0
            end
            redis.call('set', KEYS[1], ARGV[3], 'PX', ARGV[4])
            redis.call('sadd', KEYS[3], ARGV[1])
            redis.call('pexpire', KEYS[3], ARGV[4])
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * KEYS[1]=session, KEYS[2]=index key prefix
         * ARGV[1]=sessionId
         * @return 1 if a binding was deleted.
         */
        private val UNBIND_SESSION_SCRIPT = DefaultRedisScript(
            """
            local cur = redis.call('get', KEYS[1])
            if not cur then
                return 0
            end
            redis.call('del', KEYS[1])
            redis.call('srem', KEYS[2] .. string.sub(cur, 2, -2), ARGV[1])
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * KEYS[1]=session, KEYS[2]=index key prefix
         * ARGV[1]=sessionId, ARGV[2]=ttl millis
         * @return 1 if the binding exists and was extended, 0 if it is already gone.
         */
        private val REFRESH_TTL_SCRIPT = DefaultRedisScript(
            """
            local cur = redis.call('get', KEYS[1])
            if not cur then
                return 0
            end
            redis.call('pexpire', KEYS[1], ARGV[2])
            redis.call('pexpire', KEYS[2] .. string.sub(cur, 2, -2), ARGV[2])
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * KEYS[1]=source index, KEYS[2]=target index, KEYS[3]=session key prefix
         * ARGV[1]=source instanceId, ARGV[2]=target instanceId, ARGV[3]=batch size, ARGV[4]=ttl millis
         * @return number of bindings actually moved.
         *
         * Processed members are always removed from the source index — a session that has already
         * moved elsewhere (or expired) leaves a stale entry that would be counted as load and
         * re-processed on every round. Because each call drains what it looked at, calling this
         * repeatedly with cursor 0 converges.
         */
        private val REBIND_BATCH_SCRIPT = DefaultRedisScript(
            """
            local batch = redis.call('sscan', KEYS[1], '0', 'count', ARGV[3])
            local moved = 0
            for _, member in ipairs(batch[2]) do
                local sessionKey = KEYS[3] .. string.sub(member, 2, -2)
                if redis.call('get', sessionKey) == ARGV[1] then
                    redis.call('set', sessionKey, ARGV[2], 'PX', ARGV[4])
                    redis.call('sadd', KEYS[2], member)
                    moved = moved + 1
                end
                redis.call('srem', KEYS[1], member)
            end
            if moved > 0 then
                redis.call('pexpire', KEYS[2], ARGV[4])
            end
            return moved
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * KEYS[1]=instance index, KEYS[2]=session key prefix
         * ARGV[1]=instanceId, ARGV[2]=batch size
         * @return number of bindings deleted. Sessions that were rerouted to another instance in
         *   the meantime keep their binding; only their stale index entry is dropped.
         */
        private val UNBIND_BATCH_SCRIPT = DefaultRedisScript(
            """
            local batch = redis.call('sscan', KEYS[1], '0', 'count', ARGV[2])
            local deleted = 0
            for _, member in ipairs(batch[2]) do
                local sessionKey = KEYS[2] .. string.sub(member, 2, -2)
                if redis.call('get', sessionKey) == ARGV[1] then
                    redis.call('del', sessionKey)
                    deleted = deleted + 1
                end
                redis.call('srem', KEYS[1], member)
            end
            return deleted
            """.trimIndent(),
            Long::class.java,
        )

        internal val RELEASE_LOCK_SCRIPT = DefaultRedisScript(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long::class.java,
        )
    }

    override fun bindSession(sessionId: String, instanceId: String, agentId: Long?) {
        validateSessionId(sessionId)
        require(instanceId.isNotBlank() && instanceId.length <= 64) { "Invalid instance ID" }

        redisTemplate.execute(
            BIND_SCRIPT,
            listOf(sessionKey(sessionId), indexKey(instanceId), INDEX_KEY_PREFIX),
            instanceId,
            sessionId,
            SESSION_TTL_MS,
        )
        lastKnownBindings.put(sessionId, instanceId)
        log.debug("Bound session: $sessionId -> $instanceId")
    }

    override fun getInstanceId(sessionId: String): String? {
        validateSessionId(sessionId)
        return try {
            val bound = redisTemplate.opsForValue().get(sessionKey(sessionId)) as? String
            if (bound != null) {
                lastKnownBindings.put(sessionId, bound)
            }
            bound
        } catch (e: Exception) {
            val known = lastKnownBindings.getIfPresent(sessionId)
            if (known != null) {
                // Re-insert so the entry survives an outage longer than its own TTL.
                lastKnownBindings.put(sessionId, known)
                degrade.log(log, "Binding store unreachable, routing session $sessionId from this node's last-known binding", e)
            }
            known ?: throw e
        }
    }

    override fun unbindSession(sessionId: String) {
        validateSessionId(sessionId)
        redisTemplate.execute(UNBIND_SESSION_SCRIPT, listOf(sessionKey(sessionId), INDEX_KEY_PREFIX), sessionId)
        lastKnownBindings.invalidate(sessionId)
        log.debug("Unbound session: $sessionId")
    }

    override fun refreshActiveTime(sessionId: String) {
        validateSessionId(sessionId)
        try {
            // The reverse index must expire with its bindings: an index that outlives the bindings
            // it lists inflates the instance's apparent load, and one that expires first hides
            // sessions that are still routed, so rebind cannot move them.
            redisTemplate.execute(REFRESH_TTL_SCRIPT, listOf(sessionKey(sessionId), INDEX_KEY_PREFIX), sessionId, SESSION_TTL_MS)
        } catch (e: Exception) {
            log.warn("Failed to refresh Redis TTL for session $sessionId: ${e.message}")
        }
    }

    override fun rerouteSession(sessionId: String, excludeInstanceIds: Set<String>): String {
        validateSessionId(sessionId)

        // The lock only makes placement nicer (it keeps two concurrent callers from selecting the
        // same instance); correctness comes from the compare-and-set below, so a lock we cannot
        // get must not fail the request.
        val lockKey = "$LOCK_KEY_PREFIX$sessionId"
        val lockValue = UUID.randomUUID().toString()
        val locked = tryAcquireLock(lockKey, lockValue)

        try {
            repeat(REROUTE_ATTEMPTS) { attempt ->
                val candidates = instanceRegistry.getHealthyInstances()
                    .filter { it.instanceId !in excludeInstanceIds }
                // Ask the fleet first: even a session this node has never seen can be placed from the
                // registry's own degraded view, which is all an outage leaves.
                val current = try {
                    getInstanceId(sessionId)
                } catch (e: Exception) {
                    return placeUnpersisted(sessionId, null, candidates, excludeInstanceIds, e)
                }

                if (current != null && current !in excludeInstanceIds && candidates.any { it.instanceId == current }) {
                    // Another node already placed this session on an instance we accept; keep it.
                    refreshActiveTime(sessionId)
                    return current
                }

                if (candidates.isEmpty()) {
                    // Moving away from a binding is only useful if somewhere exists to move to.
                    val bound = current?.let { instanceRegistry.getInstance(it) }
                    if (bound != null && bound.isAcceptingNewSessions(heartbeatTimeoutMs)) {
                        return bound.instanceId
                    }
                    throw IllegalStateException("No healthy agent-service instances available for session $sessionId")
                }

                val target = selectLeastLoadedInstance(candidates)
                val moved = try {
                    if (current == null) {
                        claimSession(sessionId, target.instanceId)
                    } else {
                        moveSession(current, target.instanceId, sessionId)
                    }
                } catch (e: Exception) {
                    return placeUnpersisted(sessionId, current, candidates, excludeInstanceIds, e)
                }
                if (moved) {
                    lastKnownBindings.put(sessionId, target.instanceId)
                    log.info(
                        "Rerouted session $sessionId to instance ${target.instanceId}" +
                            if (current != null) " (was $current)" else "",
                    )
                    return target.instanceId
                }

                log.debug(
                    "Reroute of session $sessionId lost a race (attempt ${attempt + 1}/$REROUTE_ATTEMPTS), retrying",
                )
            }

            val current = getInstanceId(sessionId)
            if (current != null && current !in excludeInstanceIds) {
                return current
            }
            throw IllegalStateException("Unable to reroute session $sessionId: concurrent reroute")
        } finally {
            if (locked) {
                try {
                    releaseLock(lockKey, lockValue)
                } catch (e: Exception) {
                    log.error("Failed to release lock for session $sessionId: ${e.message}")
                }
            }
        }
    }

    override fun rebindAllSessions(oldInstanceId: String, newInstanceId: String): Int {
        if (oldInstanceId == newInstanceId) {
            log.warn("rebindAllSessions called with source == target ($oldInstanceId); nothing to do")
            return 0
        }

        val sourceKey = indexKey(oldInstanceId)
        val targetKey = indexKey(newInstanceId)
        var moved = 0L

        // Sessions can still be added to the source index while this runs (requests that were
        // already in flight when the instance went DOWN), so keep going until it drains.
        var batch = 0
        while (batch++ < MAX_REBIND_BATCHES) {
            val result = redisTemplate.execute(
                REBIND_BATCH_SCRIPT,
                listOf(sourceKey, targetKey, SESSION_KEY_PREFIX),
                oldInstanceId,
                newInstanceId,
                REBIND_BATCH_SIZE,
                SESSION_TTL_MS,
            )
            moved += result ?: 0L
            if ((redisTemplate.opsForSet().size(sourceKey) ?: 0L) == 0L) {
                log.info("Rebind moved $moved sessions from $oldInstanceId to $newInstanceId")
                return moved.toInt()
            }
        }

        val leftover = redisTemplate.opsForSet().size(sourceKey) ?: 0L
        log.warn(
            "Rebind from $oldInstanceId to $newInstanceId stopped after $MAX_REBIND_BATCHES batches with " +
                "$leftover index entries left; the reconciler will finish the job",
        )
        return moved.toInt()
    }

    override fun unbindInstanceSessions(instanceId: String): Int {
        val indexKey = indexKey(instanceId)
        var unbound = 0L
        var batch = 0
        while (batch++ < MAX_REBIND_BATCHES) {
            val result = redisTemplate.execute(
                UNBIND_BATCH_SCRIPT,
                listOf(indexKey, SESSION_KEY_PREFIX),
                instanceId,
                REBIND_BATCH_SIZE,
            )
            unbound += result ?: 0L
            if ((redisTemplate.opsForSet().size(indexKey) ?: 0L) == 0L) break
        }
        // Whatever is left belongs to sessions that were rerouted to another instance.
        redisTemplate.delete(indexKey)

        log.info("Unbound $unbound sessions from instance $instanceId")
        return unbound.toInt()
    }

    override fun getSessionCountByInstance(instanceId: String): Int = try {
        readSessionCount(instanceId)
    } catch (e: Exception) {
        degradedCounts(listOf(instanceId), e)[instanceId] ?: 0
    }

    override fun getSessionCountsByInstances(instanceIds: List<String>): Map<String, Int> = try {
        instanceIds.associateWith { readSessionCount(it) }
    } catch (e: Exception) {
        degradedCounts(instanceIds, e)
    }

    private fun readSessionCount(instanceId: String): Int = redisTemplate.opsForSet().size(indexKey(instanceId))?.toInt() ?: 0

    /**
     * Load as this node sees it. Only ever used while Redis is down, where "spread new sessions
     * across what looks least loaded" still beats "cannot pick an instance at all"; the numbers are
     * a single node's view, not the fleet's.
     */
    private fun degradedCounts(
        instanceIds: List<String>,
        cause: Exception,
    ): Map<String, Int> {
        val counts = lastKnownBindings.asMap().values.groupingBy { it }.eachCount()
        degrade.log(log, "Reverse index unreachable, spreading ${instanceIds.size} instance(s) on this node's bindings only", cause)
        return instanceIds.associateWith { counts[it] ?: 0 }
    }

    /**
     * Redis refused to persist a placement. The caller still needs an instance to send the request
     * to, [candidates] came from the registry's own degraded view, and a session that merely loses
     * affinity pays for a new instance — so route on it here rather than fail the request. The
     * binding is written on the next placement that succeeds once Redis is back.
     */
    private fun placeUnpersisted(
        sessionId: String,
        current: String?,
        candidates: List<AgentInstance>,
        excludeInstanceIds: Set<String>,
        cause: Exception,
    ): String {
        if (candidates.isEmpty()) {
            throw IllegalStateException("No healthy agent-service instances available for session $sessionId")
        }
        val kept = current?.takeIf { it !in excludeInstanceIds && candidates.any { candidate -> candidate.instanceId == it } }
        val target = kept ?: selectLeastLoadedInstance(candidates).instanceId
        lastKnownBindings.put(sessionId, target)
        degrade.log(log, "Cannot persist the placement of session $sessionId; routing it to $target from this node only", cause)
        return target
    }

    private fun claimSession(sessionId: String, instanceId: String): Boolean {
        val result = redisTemplate.execute(
            CLAIM_SCRIPT,
            listOf(sessionKey(sessionId), indexKey(instanceId)),
            sessionId,
            instanceId,
            SESSION_TTL_MS,
        )
        return result == 1L
    }

    private fun moveSession(
        fromInstanceId: String,
        toInstanceId: String,
        sessionId: String,
    ): Boolean {
        val result = redisTemplate.execute(
            MOVE_IF_FROM_SCRIPT,
            listOf(sessionKey(sessionId), indexKey(fromInstanceId), indexKey(toInstanceId)),
            sessionId,
            fromInstanceId,
            toInstanceId,
            SESSION_TTL_MS,
        )
        return result == 1L
    }

    private fun sessionKey(sessionId: String) = "$SESSION_KEY_PREFIX$sessionId"

    private fun indexKey(instanceId: String) = "$INDEX_KEY_PREFIX$instanceId"

    private fun validateSessionId(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
    }

    private fun tryAcquireLock(lockKey: String, lockValue: String): Boolean = try {
        redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofSeconds(LOCK_TIMEOUT_SECONDS)) == true
    } catch (e: Exception) {
        log.warn("Lock acquisition failed for $lockKey: ${e.message}")
        false
    }

    private fun releaseLock(lockKey: String, lockValue: String) {
        redisTemplate.execute(RELEASE_LOCK_SCRIPT, listOf(lockKey), lockValue)
    }

    private fun selectLeastLoadedInstance(instances: List<AgentInstance>): AgentInstance {
        if (instances.size == 1) return instances.first()

        val countMap = getSessionCountsByInstances(instances.map { it.instanceId })

        val weights = instances.map { inst ->
            val activeCount = countMap[inst.instanceId] ?: 0
            1.0 / (activeCount + 1)
        }

        val totalWeight = weights.sum()
        var random = Math.random() * totalWeight

        for ((index, weight) in weights.withIndex()) {
            random -= weight
            if (random <= 0) {
                return instances[index]
            }
        }

        return instances.last()
    }
}
