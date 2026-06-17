package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.mapper.AgentInstanceMapper
import com.agnetix.harnax.router.service.InstanceRegistry
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Local cache-based instance registry for single-node deployment.
 * Uses Caffeine for high-performance local caching with MySQL persistence.
 */
class LocalInstanceRegistry(
    private val agentInstanceMapper: AgentInstanceMapper,
    @Value("${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
    @Value("${router.cache.instance-ttl-seconds:3}")
    instanceCacheTtlSeconds: Long = 3,
) : InstanceRegistry {

    private val log = LoggerFactory.getLogger(LocalInstanceRegistry::class.java)

    private val healthyInstancesCache = Caffeine.newBuilder()
        .expireAfterWrite(2, TimeUnit.SECONDS)
        .build<String, List<AgentInstance>>()

    private val instanceCache = Caffeine.newBuilder()
        .expireAfterWrite(instanceCacheTtlSeconds, TimeUnit.SECONDS)
        .maximumSize(500)
        .recordStats()
        .build<String, AgentInstance>()

    private val lastCacheInvalidationMs = AtomicLong(0)
    private val cacheThrottleMs = 500L

    override fun registerInstance(instanceId: String, host: String, port: Int) {
        // Persist to MySQL
        agentInstanceMapper.upsertInstance(instanceId, host, port, LocalDateTime.now())

        // Invalidate local cache
        instanceCache.invalidate(instanceId)
        throttledInvalidateCache()

        log.info("Registered instance: $instanceId at $host:$port")
    }

    override fun unregisterInstance(instanceId: String) {
        // Remove from MySQL
        agentInstanceMapper.deleteByInstanceId(instanceId)

        // Invalidate local cache
        instanceCache.invalidate(instanceId)
        healthyInstancesCache.invalidateAll()

        log.info("Unregistered instance: $instanceId")
    }

    override fun refreshHeartbeat(instanceId: String) {
        val rows = agentInstanceMapper.updateHeartbeat(instanceId, LocalDateTime.now(), "UP")
        if (rows == 0) {
            log.warn("Heartbeat refresh failed - instance not found: $instanceId")
        } else {
            // Invalidate local cache
            instanceCache.invalidate(instanceId)
            throttledInvalidateCache()
        }
    }

    override fun getHealthyInstances(): List<AgentInstance> = healthyInstancesCache.get("healthy") {
        val allUp = agentInstanceMapper.selectHealthyInstances()
        allUp.filter { it.isHealthy(heartbeatTimeoutMs) }
    }

    override fun getAllActiveInstances(): List<AgentInstance> = agentInstanceMapper.selectAllInstances()

    override fun getInstance(instanceId: String): AgentInstance? = instanceCache.get(instanceId) {
        agentInstanceMapper.selectByInstanceId(instanceId)
    }

    override fun markInstanceDown(instanceId: String): Int {
        val rows = agentInstanceMapper.markAsDown(instanceId)
        instanceCache.invalidate(instanceId)
        healthyInstancesCache.invalidateAll()
        log.warn("Marked instance as DOWN: $instanceId (rows=$rows)")
        return rows
    }

    override fun markAsDraining(instanceId: String) {
        agentInstanceMapper.updateHeartbeat(instanceId, LocalDateTime.now(), "DRAINING")
        instanceCache.invalidate(instanceId)
        healthyInstancesCache.invalidateAll()
        log.info("Marked instance as DRAINING: $instanceId")
    }

    private fun throttledInvalidateCache() {
        val now = System.currentTimeMillis()
        val last = lastCacheInvalidationMs.get()
        if (now - last >= cacheThrottleMs && lastCacheInvalidationMs.compareAndSet(last, now)) {
            healthyInstancesCache.invalidateAll()
        }
    }

    /**
     * Get cache statistics for monitoring.
     */
    fun getCacheStats(): Map<String, Any> {
        val stats = instanceCache.stats()
        return mapOf(
            "hitRate" to String.format("%.2f", stats.hitRate()),
            "missRate" to String.format("%.2f", stats.missRate()),
            "hitCount" to stats.hitCount(),
            "missCount" to stats.missCount(),
            "size" to instanceCache.estimatedSize(),
            "healthyCacheSize" to healthyInstancesCache.estimatedSize(),
        )
    }
}
