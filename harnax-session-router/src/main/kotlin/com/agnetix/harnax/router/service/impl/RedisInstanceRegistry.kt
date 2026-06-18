package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import java.time.LocalDateTime

/**
 * Pure Redis-based distributed instance registry.
 * Redis is the single source of truth - no MySQL dependency.
 */
class RedisInstanceRegistry(
    private val redisTemplate: RedisTemplate<String, Any>,
    private val heartbeatTimeoutMs: Long,
) : InstanceRegistry {

    private val log = LoggerFactory.getLogger(RedisInstanceRegistry::class.java)

    companion object {
        private const val INSTANCE_KEY_PREFIX = "router:instance:"
        private const val HEALTHY_SET_KEY = "router:instances:healthy"
        private const val ALL_SET_KEY = "router:instances:all"
        private val INSTANCE_TTL = Duration.ofHours(24)
    }

    override fun registerInstance(instanceId: String, host: String, port: Int) {
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

    override fun refreshHeartbeat(instanceId: String) {
        val instanceKey = "$INSTANCE_KEY_PREFIX$instanceId"
        val exists = redisTemplate.opsForHash<String, Any>().hasKey(instanceKey, "instanceId")
        if (!exists) {
            log.warn("Heartbeat refresh failed - instance not found: $instanceId")
            return
        }
        val now = LocalDateTime.now()
        redisTemplate.opsForHash<String, Any>().put(instanceKey, "lastHeartbeat", now.toString())
        redisTemplate.opsForHash<String, Any>().put(instanceKey, "status", "UP")
        redisTemplate.expire(instanceKey, INSTANCE_TTL)
        redisTemplate.opsForSet().add(HEALTHY_SET_KEY, instanceId)
        log.debug("Refreshed heartbeat for instance: $instanceId")
    }

    override fun getHealthyInstances(): List<AgentInstance> {
        val memberIds = redisTemplate.opsForSet().members(HEALTHY_SET_KEY)
        if (memberIds.isNullOrEmpty()) return emptyList()

        val instances = mutableListOf<AgentInstance>()
        val staleIds = mutableListOf<String>()

        for (id in memberIds) {
            val instance = getInstance(id as String)
            if (instance != null && instance.isHealthy(heartbeatTimeoutMs)) {
                instances.add(instance)
            } else {
                staleIds.add(id)
            }
        }

        if (staleIds.isNotEmpty()) {
            redisTemplate.opsForSet().remove(HEALTHY_SET_KEY, *staleIds.toTypedArray())
            log.info("Cleaned up ${staleIds.size} stale instances from healthy set")
        }

        return instances
    }

    override fun getAllActiveInstances(): List<AgentInstance> {
        val memberIds = redisTemplate.opsForSet().members(ALL_SET_KEY) ?: return emptyList()
        return memberIds.mapNotNull { getInstance(it as String) }
    }

    override fun getInstance(instanceId: String): AgentInstance? {
        val instanceKey = "$INSTANCE_KEY_PREFIX$instanceId"
        val hashEntries = redisTemplate.opsForHash<String, Any>().entries(instanceKey)
        if (hashEntries.isEmpty()) return null
        return reconstructInstanceFromHash(instanceId, hashEntries)
    }

    override fun markInstanceDown(instanceId: String): Int {
        val instanceKey = "$INSTANCE_KEY_PREFIX$instanceId"
        val exists = redisTemplate.opsForHash<String, Any>().hasKey(instanceKey, "instanceId")
        if (!exists) return 0

        val currentStatus = redisTemplate.opsForHash<String, Any>().get(instanceKey, "status") as? String
        if (currentStatus == "DOWN") return 0

        redisTemplate.opsForHash<String, Any>().put(instanceKey, "status", "DOWN")
        redisTemplate.opsForSet().remove(HEALTHY_SET_KEY, instanceId)
        log.warn("Marked instance as DOWN: $instanceId")
        return 1
    }

    override fun markAsDraining(instanceId: String) {
        val instanceKey = "$INSTANCE_KEY_PREFIX$instanceId"
        val now = LocalDateTime.now()
        redisTemplate.opsForHash<String, Any>().put(instanceKey, "status", "DRAINING")
        redisTemplate.opsForHash<String, Any>().put(instanceKey, "lastHeartbeat", now.toString())
        redisTemplate.opsForSet().remove(HEALTHY_SET_KEY, instanceId)
        log.info("Marked instance as DRAINING: $instanceId")
    }

    private fun reconstructInstanceFromHash(
        instanceId: String,
        hashEntries: Map<String, Any>,
    ): AgentInstance = AgentInstance().apply {
        this.instanceId = instanceId
        this.host = hashEntries["host"] as? String ?: ""
        this.port = (hashEntries["port"] as? Number)?.toInt() ?: 0
        this.status = hashEntries["status"] as? String ?: "UP"
        this.active = (hashEntries["active"] as? Number)?.toInt() ?: 1
        val heartbeatStr = hashEntries["lastHeartbeat"] as? String
        this.lastHeartbeat = try {
            if (heartbeatStr != null) LocalDateTime.parse(heartbeatStr) else LocalDateTime.now()
        } catch (e: Exception) {
            LocalDateTime.now()
        }
    }
}
