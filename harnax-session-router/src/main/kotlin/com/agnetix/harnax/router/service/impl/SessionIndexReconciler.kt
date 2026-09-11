package com.agnetix.harnax.router.service.impl

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.util.UUID

/**
 * Repairs the `router:instance_sessions:*` reverse index, which everything else in the router only
 * updates as a side effect of routing a request.
 *
 * The index drives placement: [RedisSessionMappingService.selectLeastLoadedInstance] weights
 * instances by SCARD of their index, so entries left behind for sessions that have long expired make
 * an idle instance look loaded and skew placement for as long as the index key lives. Entries also go
 * stale when one node writes a binding while another re-places the same session, or when Redis loses
 * keys (eviction, a flush, a promotion of a lagging replica). Nothing else removes them.
 *
 * Two passes run under a cluster-wide lock, so one router node reconciles per interval:
 *  - index to binding: drop members whose session key is gone, re-point members that are bound to a
 *    different instance, and delete indexes whose instance has unregistered;
 *  - binding to index: re-add live bindings that no index lists.
 *
 * Redis mode only. Local mode updates both maps inside one `compute` call, so there is no cross-node
 * window to repair.
 */
class SessionIndexReconciler(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val sessionTtl: Duration = Duration.ofHours(24),
    private val batchSize: Int = 500,
    private val maxBatchesPerIndex: Int = 40,
    private val lockTtl: Duration = Duration.ofMinutes(4),
) {

    private val log = LoggerFactory.getLogger(SessionIndexReconciler::class.java)

    companion object {
        private const val RECONCILE_LOCK_KEY = "router:lock:index_reconcile"

        /** Upper bound on keys read per pass, so a runaway keyspace cannot pin a scheduler thread. */
        private const val MAX_KEYS_PER_PASS = 50_000

        /**
         * KEYS[1]=instance index, KEYS[2]=session key prefix, KEYS[3]=index key prefix,
         * KEYS[4]=instance key prefix
         * ARGV[1]=instanceId, ARGV[2]=ttl millis, ARGV[3..]=session ids currently listed by the index
         * @return number of entries dropped from this index.
         *
         * A member that is bound elsewhere is moved, not just dropped: deleting it would under-count
         * the instance actually serving the session, which is the very failure being repaired. A
         * member bound to an unregistered instance is only dropped — that binding gets re-placed on
         * the next request, and indexing a dead instance is what this pass exists to remove.
         */
        private val PRUNE_INDEX_SCRIPT = DefaultRedisScript(
            """
            local pruned = 0
            for i = 3, #ARGV do
                local member = ARGV[i]
                local bound = redis.call('get', KEYS[2] .. string.sub(member, 2, -2))
                if bound == false then
                    redis.call('srem', KEYS[1], member)
                    pruned = pruned + 1
                elseif bound ~= ARGV[1] then
                    redis.call('srem', KEYS[1], member)
                    pruned = pruned + 1
                    local instanceId = string.sub(bound, 2, -2)
                    if redis.call('exists', KEYS[4] .. instanceId) == 1 then
                        local target = KEYS[3] .. instanceId
                        redis.call('sadd', target, member)
                        redis.call('pexpire', target, ARGV[2])
                    end
                end
            end
            return pruned
            """.trimIndent(),
            Long::class.java,
        )

        /**
         * KEYS[1]=index key prefix, KEYS[2]=session key prefix, KEYS[3]=instance key prefix,
         * KEYS[4..]=session keys
         * ARGV[1]=ttl millis
         * @return number of index entries added.
         *
         * Session keys are passed as KEYS entries because keys are string-serialized (no quotes),
         * while the bound instance id read back is JSON-serialized, so its quotes are stripped before
         * it becomes part of a key name. The member is written quoted to match what the value
         * serializer stores everywhere else — an unquoted member would be mangled by the
         * `string.sub(member, 2, -2)` the other scripts use.
         *
         * Bindings that point at an unregistered instance are skipped rather than indexed: the next
         * request re-places them, and an index for a dead instance is what the prune pass deletes.
         */
        private val RESTORE_INDEX_SCRIPT = DefaultRedisScript(
            """
            local added = 0
            for i = 4, #KEYS do
                local bound = redis.call('get', KEYS[i])
                if bound then
                    local instanceId = string.sub(bound, 2, -2)
                    if redis.call('exists', KEYS[3] .. instanceId) == 1 then
                        local member = '"' .. string.sub(KEYS[i], #KEYS[2] + 1) .. '"'
                        local target = KEYS[1] .. instanceId
                        if redis.call('sismember', target, member) == 0 then
                            redis.call('sadd', target, member)
                            redis.call('pexpire', target, ARGV[1])
                            added = added + 1
                        end
                    end
                end
            end
            return added
            """.trimIndent(),
            Long::class.java,
        )

        private val ACQUIRE_LOCK_SCRIPT = DefaultRedisScript(
            "if redis.call('set', KEYS[1], ARGV[1], 'PX', ARGV[2], 'NX') then return 1 else return 0 end",
            Long::class.java,
        )
    }

    @Scheduled(
        fixedDelayString = "\${router.reconcile.interval-ms:300000}",
        initialDelayString = "\${router.reconcile.initial-delay-ms:120000}",
    )
    fun reconcile() {
        val token = UUID.randomUUID().toString()
        if (!tryLock(token)) {
            log.debug("Another router node holds the reconcile lock; skipping this round")
            return
        }

        val started = System.currentTimeMillis()
        try {
            val pruned = pruneStaleEntries()
            val restored = restoreMissingEntries()
            val elapsed = System.currentTimeMillis() - started
            if (pruned > 0 || restored > 0) {
                log.info("Reconciled the session index: dropped $pruned stale entries, restored $restored missing ones ($elapsed ms)")
            } else {
                log.debug("Session index was consistent ($elapsed ms)")
            }
        } catch (e: Exception) {
            log.warn("Session index reconciliation failed: ${e.message}")
        } finally {
            try {
                redisTemplate.execute(RedisSessionMappingService.RELEASE_LOCK_SCRIPT, listOf(RECONCILE_LOCK_KEY), token)
            } catch (e: Exception) {
                log.debug("Cannot release the reconcile lock, it expires on its own: ${e.message}")
            }
        }
    }

    /** @return entries dropped from indexes of instances that are still registered. */
    private fun pruneStaleEntries(): Long {
        var pruned = 0L
        val indexKeys = scanKeys("${RedisSessionMappingService.INDEX_KEY_PREFIX}*")
        for (indexKey in indexKeys) {
            val instanceId = indexKey.removePrefix(RedisSessionMappingService.INDEX_KEY_PREFIX)
            if (instanceId.isEmpty()) continue

            if (!redisTemplate.hasKey("${RedisInstanceRegistry.INSTANCE_KEY_PREFIX}$instanceId")) {
                // The instance unregistered, so nothing routes here any more. A leftover index would
                // keep being weighed by every placement decision.
                redisTemplate.delete(indexKey)
                log.info("Dropped the session index of unregistered instance $instanceId")
                continue
            }

            var batches = 0
            val batch = mutableListOf<Any>()
            redisTemplate
                .opsForSet()
                .scan(indexKey, ScanOptions.scanOptions().count(batchSize.toLong()).build())
                .use { cursor ->
                    while (cursor.hasNext() && batches < maxBatchesPerIndex) {
                        cursor.next()?.let { batch.add(it) }
                        if (batch.size >= batchSize) {
                            pruned += runPrune(indexKey, instanceId, batch)
                            batch.clear()
                            batches++
                        }
                    }
                }
            if (batch.isNotEmpty()) {
                pruned += runPrune(indexKey, instanceId, batch)
            }
            if (batches >= maxBatchesPerIndex) {
                log.info("The index of $instanceId is still large after $maxBatchesPerIndex batches; the next round continues")
            }
        }
        return pruned
    }

    private fun runPrune(
        indexKey: String,
        instanceId: String,
        batch: List<Any>,
    ): Long {
        val args = listOf(instanceId as Any, sessionTtl.toMillis() as Any) + batch
        val result = redisTemplate.execute(
            PRUNE_INDEX_SCRIPT,
            listOf(
                indexKey,
                RedisSessionMappingService.SESSION_KEY_PREFIX,
                RedisSessionMappingService.INDEX_KEY_PREFIX,
                RedisInstanceRegistry.INSTANCE_KEY_PREFIX,
            ),
            *args.toTypedArray(),
        )
        return result ?: 0L
    }

    /** @return index entries re-added from live bindings. */
    private fun restoreMissingEntries(): Long {
        var restored = 0L
        val batch = mutableListOf<String>()
        for (sessionKey in scanKeys("${RedisSessionMappingService.SESSION_KEY_PREFIX}*")) {
            batch.add(sessionKey)
            if (batch.size >= batchSize) {
                restored += runRestore(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) {
            restored += runRestore(batch)
        }
        return restored
    }

    private fun runRestore(batch: List<String>): Long {
        val result = redisTemplate.execute(
            RESTORE_INDEX_SCRIPT,
            listOf(
                RedisSessionMappingService.INDEX_KEY_PREFIX,
                RedisSessionMappingService.SESSION_KEY_PREFIX,
                RedisInstanceRegistry.INSTANCE_KEY_PREFIX,
            ) + batch,
            sessionTtl.toMillis(),
        )
        return result ?: 0L
    }

    /**
     * Keyspace scan for a pattern. [K] is String, so the names come back ready to use as keys.
     */
    private fun scanKeys(pattern: String): List<String> {
        val keys = mutableListOf<String>()
        redisTemplate
            .scan(ScanOptions.scanOptions().match(pattern).count(batchSize.toLong()).build())
            .use { cursor ->
                while (cursor.hasNext() && keys.size < MAX_KEYS_PER_PASS) {
                    cursor.next()?.let { keys.add(it) }
                }
            }
        if (keys.size >= MAX_KEYS_PER_PASS) {
            log.warn("Stopped the keyspace scan for $pattern at $MAX_KEYS_PER_PASS keys; the next round continues")
        }
        return keys
    }

    private fun tryLock(token: String): Boolean = try {
        redisTemplate.execute(ACQUIRE_LOCK_SCRIPT, listOf(RECONCILE_LOCK_KEY), token, lockTtl.toMillis()) == 1L
    } catch (e: Exception) {
        log.warn("Cannot reach Redis, skipping reconciliation: ${e.message}")
        false
    }
}
