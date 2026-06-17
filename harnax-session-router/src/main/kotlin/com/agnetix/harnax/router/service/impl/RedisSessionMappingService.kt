package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.mapper.SessionMappingMapper
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import java.time.LocalDateTime

/**
 * Redis-based distributed session mapping service.
 * Ensures session stickiness across multiple router nodes.
 */
class RedisSessionMappingService(
    private val sessionMappingMapper: SessionMappingMapper,
    private val instanceRegistry: InstanceRegistry,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val heartbeatTimeoutMs: Long = 30000L,
) : SessionMappingService {

    private val log = LoggerFactory.getLogger(RedisSessionMappingService::class.java)

    companion object {
        private const val SESSION_KEY_PREFIX = "router:session:"
        private const val LOCK_KEY_PREFIX = "router:lock:session:"
        private const val LOCK_TIMEOUT_SECONDS = 5L
        private const val SESSION_TTL_HOURS = 24L // Sessions expire after 24 hours of inactivity
    }

    override fun bindSession(sessionId: String, instanceId: String, agentId: Long?) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
        require(instanceId.isNotBlank() && instanceId.length <= 64) { "Invalid instance ID" }

        // Persist to MySQL for durability
        sessionMappingMapper.upsertBinding(sessionId, instanceId, agentId, LocalDateTime.now())

        // Cache in Redis for fast lookup
        val sessionKey = "$SESSION_KEY_PREFIX$sessionId"
        redisTemplate.opsForValue().set(sessionKey, instanceId, Duration.ofHours(SESSION_TTL_HOURS))

        log.debug("Bound session: $sessionId -> $instanceId")
    }

    override fun getInstanceId(sessionId: String): String? {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        // Try Redis first (fast path)
        return try {
            val sessionKey = "$SESSION_KEY_PREFIX$sessionId"
            val cachedInstanceId = redisTemplate.opsForValue().get(sessionKey) as? String

            if (cachedInstanceId != null) {
                return cachedInstanceId
            }

            // Cache miss - load from MySQL
            loadFromMySQLAndCache(sessionId, sessionKey)
        } catch (e: Exception) {
            log.error("Redis error in getInstanceId($sessionId), falling back to MySQL: ${e.message}")
            // Fallback: direct MySQL query
            sessionMappingMapper.selectBySessionId(sessionId)?.instanceId
        }
    }

    private fun loadFromMySQLAndCache(sessionId: String, sessionKey: String): String? {
        return try {
            val mapping = sessionMappingMapper.selectBySessionId(sessionId)
            val instanceId = mapping?.instanceId

            if (instanceId != null) {
                try {
                    // Populate Redis cache (best-effort)
                    redisTemplate.opsForValue().set(sessionKey, instanceId, Duration.ofHours(SESSION_TTL_HOURS))
                } catch (e: Exception) {
                    log.warn("Failed to cache session mapping in Redis: ${e.message}")
                }
            }

            instanceId
        } catch (e: Exception) {
            log.error("Failed to load session mapping from MySQL: $sessionId", e)
            null
        }
    }

    override fun unbindSession(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        // Remove from MySQL
        sessionMappingMapper.deleteBySessionId(sessionId)

        // Remove from Redis
        val sessionKey = "$SESSION_KEY_PREFIX$sessionId"
        redisTemplate.delete(sessionKey)

        log.debug("Unbound session: $sessionId")
    }

    override fun refreshActiveTime(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        sessionMappingMapper.refreshActiveTime(sessionId, LocalDateTime.now())

        try {
            val sessionKey = "$SESSION_KEY_PREFIX$sessionId"
            redisTemplate.expire(sessionKey, Duration.ofHours(SESSION_TTL_HOURS))
        } catch (e: Exception) {
            log.warn("Failed to refresh Redis TTL for session $sessionId: ${e.message}")
        }
    }

    override fun rerouteSession(sessionId: String): String {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        // Use distributed lock to prevent race conditions
        val lockKey = "$LOCK_KEY_PREFIX$sessionId"
        val locked = tryAcquireLockWithRetry(lockKey, maxRetries = 3)

        if (!locked) {
            log.warn("Failed to acquire lock for session reroute after retries: $sessionId")
            // Fallback: return existing binding if still healthy
            val existingInstanceId = getInstanceId(sessionId)
            if (existingInstanceId != null) {
                try {
                    val existingInstance = instanceRegistry.getInstance(existingInstanceId)
                    if (existingInstance?.isHealthy(heartbeatTimeoutMs) == true &&
                        !existingInstance.isDraining()
                    ) {
                        log.info("Returning existing healthy instance for session $sessionId (lock contention)")
                        return existingInstanceId
                    }
                } catch (e: Exception) {
                    log.error("Error checking instance health during lock contention: ${e.message}")
                }
            }
            throw IllegalStateException("Concurrent reroute in progress for session: $sessionId")
        }

        try {
            val healthyInstances = instanceRegistry.getHealthyInstances()
            if (healthyInstances.isEmpty()) {
                throw IllegalStateException("No healthy agent-service instances available")
            }

            val newInstance = selectLeastLoadedInstance(healthyInstances)

            // Atomically update binding
            bindSession(sessionId, newInstance.instanceId)

            log.info("Rerouted session $sessionId to instance ${newInstance.instanceId}")
            return newInstance.instanceId
        } finally {
            try {
                releaseLock(lockKey)
            } catch (e: Exception) {
                log.error("Failed to release lock for session $sessionId: ${e.message}")
                // Lock will auto-expire after TTL, non-critical
            }
        }
    }

    /**
     * Try to acquire a distributed lock with retry mechanism.
     */
    private fun tryAcquireLockWithRetry(lockKey: String, maxRetries: Int): Boolean {
        for (attempt in 1..maxRetries) {
            try {
                val result = redisTemplate.opsForValue().setIfAbsent(
                    lockKey,
                    Thread.currentThread().name,
                    Duration.ofSeconds(LOCK_TIMEOUT_SECONDS),
                )
                if (result == true) {
                    return true
                }

                // Lock held by another node - wait and retry
                if (attempt < maxRetries) {
                    Thread.sleep(50L * attempt) // Exponential backoff: 50ms, 100ms, 150ms
                }
            } catch (e: Exception) {
                log.warn("Lock acquisition attempt $attempt failed for $lockKey: ${e.message}")
                if (attempt < maxRetries) {
                    Thread.sleep(50L * attempt)
                }
            }
        }
        return false
    }

    override fun rebindAllSessions(oldInstanceId: String, newInstanceId: String): Int {
        val count = sessionMappingMapper.rebindSessions(oldInstanceId, newInstanceId)

        // Update Redis cache for affected sessions
        val sessions = sessionMappingMapper.selectByInstanceId(newInstanceId)
        for (mapping in sessions) {
            val sessionKey = "$SESSION_KEY_PREFIX${mapping.sessionId}"
            redisTemplate.opsForValue().set(sessionKey, newInstanceId, Duration.ofHours(SESSION_TTL_HOURS))
        }

        log.info("Rebind $count sessions from $oldInstanceId to $newInstanceId")
        return count
    }

    override fun unbindInstanceSessions(instanceId: String): Int {
        val sessions = sessionMappingMapper.selectByInstanceId(instanceId)
        val count = sessionMappingMapper.deleteByInstanceId(instanceId)

        val sessionKeys = sessions.map { "$SESSION_KEY_PREFIX${it.sessionId}" }.toTypedArray()
        if (sessionKeys.isNotEmpty()) {
            redisTemplate.delete(sessionKeys.asList())
        }

        log.info("Unbound $count sessions from instance $instanceId")
        return count
    }

    /**
     * Select the least loaded instance based on active session count.
     * Uses weighted random selection for better distribution.
     */
    private fun selectLeastLoadedInstance(instances: List<AgentInstance>): AgentInstance {
        if (instances.size == 1) return instances.first()

        // Get session counts for all instances
        val instanceIds = instances.map { it.instanceId }
        val countResults = sessionMappingMapper.countSessionsByInstances(instanceIds)

        val countMap = mutableMapOf<String, Int>()
        for (row in countResults) {
            val id = (row["instance_id"] ?: row["instanceId"]) as? String ?: continue
            val cnt = (row["cnt"] as? Number)?.toInt() ?: 0
            countMap[id] = cnt
        }

        // Weighted random: weight = 1 / (activeCount + 1)
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

    /**
     * Release a distributed lock.
     */
    private fun releaseLock(lockKey: String) {
        redisTemplate.delete(lockKey)
    }
}
