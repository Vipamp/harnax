package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.mapper.AgentInstanceMapper
import com.agnetix.harnax.router.service.InstanceRegistry
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Service
class MysqlInstanceRegistry(
    private val agentInstanceMapper: AgentInstanceMapper,
    @Value($$"${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
) : InstanceRegistry {

    private val log = LoggerFactory.getLogger(MysqlInstanceRegistry::class.java)

    private val healthyInstancesCache = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.SECONDS)
        .build<String, List<AgentInstance>>()

    private val lastCacheInvalidationMs = AtomicLong(0)
    private val cacheThrottleMs = 2000L

    override fun registerInstance(instanceId: String, host: String, port: Int) {
        agentInstanceMapper.upsertInstance(instanceId, host, port, LocalDateTime.now())
        throttledInvalidateCache()
        log.info("Registered instance: $instanceId at $host:$port")
    }

    override fun unregisterInstance(instanceId: String) {
        agentInstanceMapper.deleteByInstanceId(instanceId)
        healthyInstancesCache.invalidateAll()
        log.info("Unregistered instance: $instanceId")
    }

    override fun refreshHeartbeat(instanceId: String) {
        val rows = agentInstanceMapper.updateHeartbeat(instanceId, LocalDateTime.now(), "UP")
        if (rows == 0) {
            log.warn("Heartbeat refresh failed - instance not found: $instanceId")
        } else {
            throttledInvalidateCache()
        }
    }

    override fun getHealthyInstances(): List<AgentInstance> =
        healthyInstancesCache.get("healthy") {
            val allUp = agentInstanceMapper.selectHealthyInstances()
            allUp.filter { it.isHealthy(heartbeatTimeoutMs) }
        }

    override fun getAllActiveInstances(): List<AgentInstance> = agentInstanceMapper.selectAllInstances()

    override fun getInstance(instanceId: String): AgentInstance? = agentInstanceMapper.selectByInstanceId(instanceId)

    override fun markInstanceDown(instanceId: String): Int {
        val rows = agentInstanceMapper.markAsDown(instanceId)
        healthyInstancesCache.invalidateAll()
        log.warn("Marked instance as DOWN: $instanceId (rows=$rows)")
        return rows
    }

    override fun markAsDraining(instanceId: String) {
        agentInstanceMapper.updateHeartbeat(instanceId, LocalDateTime.now(), "DRAINING")
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
}
