package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.mapper.AgentInstanceMapper
import com.agnetix.harnax.router.service.InstanceRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import java.time.LocalDateTime

/**
 * Redis-based distributed instance registry.
 * Replaces local Caffeine cache with shared Redis state for multi-node consistency.
 */
class RedisInstanceRegistry(
    private val agentInstanceMapper: AgentInstanceMapper,
    private val redisTemplate: RedisTemplate<String, Any>,
    @Value("${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
) : InstanceRegistry {

    private val log = LoggerFactory.getLogger(RedisInstanceRegistry::class.java)

    companion object {
        private const val INSTANCE_KEY_PREFIX = "router:instance:"
        private const val HEALTHY_SET_KEY = "router:instances:healthy"
        private const val ALL_SET_KEY = "router:instances:all"
    }

    override fun registerInstance(instanceId: String, host: String, port: Int) {
        // Persist to MySQL for durability (authoritative)
        try {
            agentInstanceMapper.upsertInstance(instanceId, host, port, LocalDateTime.now())
        } catch (e: Exception) {
            log.error("Failed to persist instance to MySQL: $instanceId", e)
            throw e // MySQL failure is critical
        }

        // Update Redis cache (best-effort)
        try {
            val instanceKey = "$INSTANCE_KEY_PREFIX$instanceId"
            val instanceData = mapOf(
                "instanceId" to instanceId,
                "host" to host,
                "port" to port,
                "status" to "UP",
                "lastHeartbeat" to LocalDateTime.now().toString(),
                "active" to 1,
            )
            redisTemplate.opsForHash<String, Any>().putAll(instanceKey, instanceData)
            redisTemplate.expire(instanceKey, Duration.ofHours(24))

            // Add to healthy and all sets
            redisTemplate.opsForSet().add(HEALTHY_SET_KEY, instanceId)
            redisTemplate.opsForSet().add(ALL_SET_KEY, instanceId)
        } catch (e: Exception) {
            log.warn("Redis update failed during registration, but MySQL persisted: $instanceId - ${e.message}")
            // Non-critical: MySQL is source of truth
        }

        log.info("Registered instance: $instanceId at $host:$port")
    }

    override fun unregisterInstance(instanceId: String) {
        // Remove from MySQL
        agentInstanceMapper.deleteByInstanceId(instanceId)

        // Remove from Redis
        val instanceKey = "$INSTANCE_KEY_PREFIX$instanceId"
        redisTemplate.delete(instanceKey)
        redisTemplate.opsForSet().remove(HEALTHY_SET_KEY, instanceId)
        redisTemplate.opsForSet().remove(ALL_SET_KEY, instanceId)

        log.info("Unregistered instance: $instanceId")
    }

    override fun refreshHeartbeat(instanceId: String) {
        val now = LocalDateTime.now()
        val rows = agentInstanceMapper.updateHeartbeat(instanceId, now, "UP")

        if (rows == 0) {
            log.warn("Heartbeat refresh failed - instance not found: $instanceId")
        } else {
            // Update Redis hash
            val instanceKey = "$INSTANCE_KEY_PREFIX$instanceId"
            redisTemplate.opsForHash<String, Any>().put(instanceKey, "lastHeartbeat", now.toString())
            redisTemplate.opsForHash<String, Any>().put(instanceKey, "status", "UP")
            redisTemplate.expire(instanceKey, Duration.ofHours(24))

            // Ensure in healthy set
            redisTemplate.opsForSet().add(HEALTHY_SET_KEY, instanceId)

            log.debug("Refreshed heartbeat for instance: $instanceId")
        }
    }

    override fun getHealthyInstances(): List<AgentInstance> {
        return try {
            // Try to get from Redis set first
            val memberIds = redisTemplate.opsForSet().members(HEALTHY_SET_KEY)

            if (memberIds.isNullOrEmpty()) {
                // Cache miss - load from MySQL and populate Redis
                return reloadHealthyInstancesFromDatabase()
            }

            // Batch fetch instances from Redis hashes
            val instances = mutableListOf<AgentInstance>()
            val staleIds = mutableListOf<String>()

            for (id in memberIds) {
                val instance = getInstance(id as String)
                if (instance != null && instance.isHealthy(heartbeatTimeoutMs)) {
                    instances.add(instance)
                } else {
                    staleIds.add(id as String)
                }
            }

            // Clean up stale entries
            if (staleIds.isNotEmpty()) {
                try {
                    redisTemplate.opsForSet().remove(HEALTHY_SET_KEY, *staleIds.toTypedArray())
                    log.info("Cleaned up ${staleIds.size} stale instances from healthy set")
                } catch (e: Exception) {
                    log.warn("Failed to clean up stale instances: ${e.message}")
                }
            }

            instances
        } catch (e: Exception) {
            log.error("Redis error in getHealthyInstances, falling back to MySQL: ${e.message}")
            // Fallback: direct MySQL query
            agentInstanceMapper.selectHealthyInstances()
                .filter { it.isHealthy(heartbeatTimeoutMs) }
        }
    }

    override fun getAllActiveInstances(): List<AgentInstance> {
        val memberIds = redisTemplate.opsForSet().members(ALL_SET_KEY)
            ?: return agentInstanceMapper.selectAllInstances()

        return memberIds.mapNotNull { getInstance(it as String) }
    }

    override fun getInstance(instanceId: String): AgentInstance? {
        return try {
            val instanceKey = "$INSTANCE_KEY_PREFIX$instanceId"

            // Try Redis hash first
            val hashEntries = redisTemplate.opsForHash<String, Any>().entries(instanceKey)

            if (hashEntries.isNotEmpty()) {
                return reconstructInstanceFromHash(instanceId, hashEntries)
            }

            // Cache miss - load from MySQL
            val instance = agentInstanceMapper.selectByInstanceId(instanceId)
            if (instance != null) {
                try {
                    cacheInstanceInRedis(instance)
                } catch (e: Exception) {
                    log.warn("Failed to cache instance in Redis: ${e.message}")
                }
            }
            instance
        } catch (e: Exception) {
            log.error("Redis error in getInstance($instanceId), falling back to MySQL: ${e.message}")
            // Fallback: direct MySQL query
            agentInstanceMapper.selectByInstanceId(instanceId)
        }
    }

    override fun markInstanceDown(instanceId: String): Int {
        val rows = agentInstanceMapper.markAsDown(instanceId)

        if (rows > 0) {
            // Update Redis
            val instanceKey = "$INSTANCE_KEY_PREFIX$instanceId"
            redisTemplate.opsForHash<String, Any>().put(instanceKey, "status", "DOWN")
            redisTemplate.opsForSet().remove(HEALTHY_SET_KEY, instanceId)

            log.warn("Marked instance as DOWN: $instanceId")
        }

        return rows
    }

    override fun markAsDraining(instanceId: String) {
        val now = LocalDateTime.now()
        agentInstanceMapper.updateHeartbeat(instanceId, now, "DRAINING")

        // Update Redis
        val instanceKey = "$INSTANCE_KEY_PREFIX$instanceId"
        redisTemplate.opsForHash<String, Any>().put(instanceKey, "status", "DRAINING")
        redisTemplate.opsForHash<String, Any>().put(instanceKey, "lastHeartbeat", now.toString())
        redisTemplate.opsForSet().remove(HEALTHY_SET_KEY, instanceId)

        log.info("Marked instance as DRAINING: $instanceId")
    }

    /**
     * Reload healthy instances from MySQL and repopulate Redis cache.
     * This is called when Redis cache is empty or needs refresh.
     */
    private fun reloadHealthyInstancesFromDatabase(): List<AgentInstance> {
        val instances = agentInstanceMapper.selectHealthyInstances()
            .filter { it.isHealthy(heartbeatTimeoutMs) }

        // Repopulate Redis
        val healthyIds = instances.map { it.instanceId }.toTypedArray()
        if (healthyIds.isNotEmpty()) {
            redisTemplate.opsForSet().add(HEALTHY_SET_KEY, *healthyIds)
            instances.forEach { cacheInstanceInRedis(it) }
        }

        log.info("Reloaded ${instances.size} healthy instances from database")
        return instances
    }

    /**
     * Reconstruct AgentInstance object from Redis hash entries.
     */
    private fun reconstructInstanceFromHash(
        instanceId: String,
        hashEntries: Map<String, Any>,
    ): AgentInstance {
        return AgentInstance().apply {
            this.instanceId = instanceId
            this.host = hashEntries["host"] as? String ?: ""
            this.port = (hashEntries["port"] as? Number)?.toInt() ?: 0
            this.status = hashEntries["status"] as? String ?: "UP"
            this.active = (hashEntries["active"] as? Number)?.toInt() ?: 1

            val heartbeatStr = hashEntries["lastHeartbeat"] as? String
            this.lastHeartbeat = if (heartbeatStr != null) {
                try {
                    LocalDateTime.parse(heartbeatStr)
                } catch (e: Exception) {
                    LocalDateTime.now()
                }
            } else {
                LocalDateTime.now()
            }
        }
    }

    /**
     * Cache an AgentInstance object in Redis hash.
     */
    private fun cacheInstanceInRedis(instance: AgentInstance) {
        val instanceKey = "$INSTANCE_KEY_PREFIX${instance.instanceId}"
        val instanceData = mapOf(
            "instanceId" to instance.instanceId,
            "host" to instance.host,
            "port" to instance.port,
            "status" to instance.status,
            "lastHeartbeat" to instance.lastHeartbeat.toString(),
            "active" to instance.active,
        )
        redisTemplate.opsForHash<String, Any>().putAll(instanceKey, instanceData)
        redisTemplate.expire(instanceKey, Duration.ofHours(24))
    }
}
