package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.util.UUID

/**
 * Pure Redis-based distributed session mapping service.
 * Redis is the single source of truth - no MySQL dependency.
 */
class RedisSessionMappingService(
    private val instanceRegistry: InstanceRegistry,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val heartbeatTimeoutMs: Long = 30000L,
) : SessionMappingService {

    private val log = LoggerFactory.getLogger(RedisSessionMappingService::class.java)

    companion object {
        private const val SESSION_KEY_PREFIX = "router:session:"
        private const val INSTANCE_SESSIONS_KEY_PREFIX = "router:instance_sessions:"
        private const val LOCK_KEY_PREFIX = "router:lock:session:"
        private const val LOCK_TIMEOUT_SECONDS = 5L
        private val SESSION_TTL = Duration.ofHours(24)

        private val RELEASE_LOCK_SCRIPT = DefaultRedisScript(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long::class.java,
        )
    }

    override fun bindSession(sessionId: String, instanceId: String, agentId: Long?) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
        require(instanceId.isNotBlank() && instanceId.length <= 64) { "Invalid instance ID" }

        // Remove old binding if exists
        val sessionKey = "$SESSION_KEY_PREFIX$sessionId"
        val oldInstanceId = redisTemplate.opsForValue().get(sessionKey) as? String
        if (oldInstanceId != null && oldInstanceId != instanceId) {
            redisTemplate.opsForSet().remove("$INSTANCE_SESSIONS_KEY_PREFIX$oldInstanceId", sessionId)
        }

        // Set new binding
        redisTemplate.opsForValue().set(sessionKey, instanceId, SESSION_TTL)

        // Add to reverse index
        val instanceSessionsKey = "$INSTANCE_SESSIONS_KEY_PREFIX$instanceId"
        redisTemplate.opsForSet().add(instanceSessionsKey, sessionId)
        redisTemplate.expire(instanceSessionsKey, SESSION_TTL)

        log.debug("Bound session: $sessionId -> $instanceId")
    }

    override fun getInstanceId(sessionId: String): String? {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
        val sessionKey = "$SESSION_KEY_PREFIX$sessionId"
        return redisTemplate.opsForValue().get(sessionKey) as? String
    }

    override fun unbindSession(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        val sessionKey = "$SESSION_KEY_PREFIX$sessionId"
        val instanceId = redisTemplate.opsForValue().get(sessionKey) as? String

        redisTemplate.delete(sessionKey)

        if (instanceId != null) {
            redisTemplate.opsForSet().remove("$INSTANCE_SESSIONS_KEY_PREFIX$instanceId", sessionId)
        }

        log.debug("Unbound session: $sessionId")
    }

    override fun refreshActiveTime(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        val sessionKey = "$SESSION_KEY_PREFIX$sessionId"
        try {
            redisTemplate.expire(sessionKey, SESSION_TTL)
        } catch (e: Exception) {
            log.warn("Failed to refresh Redis TTL for session $sessionId: ${e.message}")
        }
    }

    override fun rerouteSession(sessionId: String): String {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        val lockKey = "$LOCK_KEY_PREFIX$sessionId"
        val lockValue = UUID.randomUUID().toString()
        val locked = tryAcquireLockWithRetry(lockKey, lockValue, maxRetries = 3)

        if (!locked) {
            log.warn("Failed to acquire lock for session reroute after retries: $sessionId")
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
            bindSession(sessionId, newInstance.instanceId)

            log.info("Rerouted session $sessionId to instance ${newInstance.instanceId}")
            return newInstance.instanceId
        } finally {
            try {
                releaseLock(lockKey, lockValue)
            } catch (e: Exception) {
                log.error("Failed to release lock for session $sessionId: ${e.message}")
            }
        }
    }

    override fun rebindAllSessions(oldInstanceId: String, newInstanceId: String): Int {
        val instanceSessionsKey = "$INSTANCE_SESSIONS_KEY_PREFIX$oldInstanceId"
        val sessions = redisTemplate.opsForSet().members(instanceSessionsKey)?.map { it as String } ?: emptyList()

        if (sessions.isEmpty()) return 0

        val ttl = SESSION_TTL
        for (sessionId in sessions) {
            val sessionKey = "$SESSION_KEY_PREFIX$sessionId"
            redisTemplate.opsForValue().set(sessionKey, newInstanceId, ttl)
            redisTemplate.opsForSet().add("$INSTANCE_SESSIONS_KEY_PREFIX$newInstanceId", sessionId)
        }

        redisTemplate.delete(instanceSessionsKey)

        log.info("Rebind ${sessions.size} sessions from $oldInstanceId to $newInstanceId")
        return sessions.size
    }

    override fun unbindInstanceSessions(instanceId: String): Int {
        val instanceSessionsKey = "$INSTANCE_SESSIONS_KEY_PREFIX$instanceId"
        val sessions = redisTemplate.opsForSet().members(instanceSessionsKey)?.map { it as String } ?: emptyList()

        val sessionKeys = sessions.map { "$SESSION_KEY_PREFIX$it" }
        if (sessionKeys.isNotEmpty()) {
            redisTemplate.delete(sessionKeys)
        }
        redisTemplate.delete(instanceSessionsKey)

        log.info("Unbound ${sessions.size} sessions from instance $instanceId")
        return sessions.size
    }

    override fun getSessionCountByInstance(instanceId: String): Int {
        val instanceSessionsKey = "$INSTANCE_SESSIONS_KEY_PREFIX$instanceId"
        return redisTemplate.opsForSet().size(instanceSessionsKey)?.toInt() ?: 0
    }

    override fun getSessionCountsByInstances(instanceIds: List<String>): Map<String, Int> =
        instanceIds.associateWith { getSessionCountByInstance(it) }

    private fun tryAcquireLockWithRetry(lockKey: String, lockValue: String, maxRetries: Int): Boolean {
        for (attempt in 1..maxRetries) {
            try {
                val result = redisTemplate.opsForValue().setIfAbsent(
                    lockKey,
                    lockValue,
                    Duration.ofSeconds(LOCK_TIMEOUT_SECONDS),
                )
                if (result == true) return true
                if (attempt < maxRetries) Thread.sleep(50L * attempt)
            } catch (e: Exception) {
                log.warn("Lock acquisition attempt $attempt failed for $lockKey: ${e.message}")
                if (attempt < maxRetries) Thread.sleep(50L * attempt)
            }
        }
        return false
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

    private fun releaseLock(lockKey: String, lockValue: String) {
        redisTemplate.execute(RELEASE_LOCK_SCRIPT, listOf(lockKey), lockValue)
    }
}
