package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.mapper.SessionMappingMapper
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Local cache-based session mapping service for single-node development/testing.
 * Uses Caffeine for session-to-instance mapping caching and ConcurrentHashMap-based
 * local locks for reroute concurrency control.
 */
class CaffeineSessionMappingService(
    private val sessionMappingMapper: SessionMappingMapper,
    private val instanceRegistry: InstanceRegistry,
    sessionCacheTtlSeconds: Long = 300,
) : SessionMappingService {

    private val log = LoggerFactory.getLogger(CaffeineSessionMappingService::class.java)

    private val sessionCache = Caffeine.newBuilder()
        .expireAfterWrite(sessionCacheTtlSeconds, TimeUnit.SECONDS)
        .maximumSize(50_000)
        .recordStats()
        .build<String, String>()

    private val rerouteLocks = ConcurrentHashMap<String, ReentrantLock>()

    override fun bindSession(sessionId: String, instanceId: String, agentId: Long?) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
        require(instanceId.isNotBlank() && instanceId.length <= 64) { "Invalid instance ID" }

        sessionMappingMapper.upsertBinding(sessionId, instanceId, agentId, LocalDateTime.now())
        sessionCache.put(sessionId, instanceId)

        log.debug("Bound session: $sessionId -> $instanceId")
    }

    override fun getInstanceId(sessionId: String): String? {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        val cached = sessionCache.getIfPresent(sessionId)
        if (cached != null) {
            return cached
        }

        val mapping = sessionMappingMapper.selectBySessionId(sessionId)
        val instanceId = mapping?.instanceId
        if (instanceId != null) {
            sessionCache.put(sessionId, instanceId)
        }
        return instanceId
    }

    override fun unbindSession(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        sessionMappingMapper.deleteBySessionId(sessionId)
        sessionCache.invalidate(sessionId)

        log.debug("Unbound session: $sessionId")
    }

    override fun refreshActiveTime(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        sessionMappingMapper.refreshActiveTime(sessionId, LocalDateTime.now())
    }

    override fun rerouteSession(sessionId: String): String {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        val lock = rerouteLocks.computeIfAbsent(sessionId) { ReentrantLock() }
        val locked = try {
            lock.tryLock(3, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!locked) {
            log.warn("Failed to acquire lock for session reroute: $sessionId")
            val existingInstanceId = getInstanceId(sessionId)
            if (existingInstanceId != null) {
                val existingInstance = instanceRegistry.getInstance(existingInstanceId)
                if (existingInstance != null && !existingInstance.isDraining()) {
                    log.info("Returning existing instance for session $sessionId (lock contention)")
                    return existingInstanceId
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
            lock.unlock()
            rerouteLocks.remove(sessionId)
        }
    }

    override fun rebindAllSessions(oldInstanceId: String, newInstanceId: String): Int {
        val count = sessionMappingMapper.rebindSessions(oldInstanceId, newInstanceId)

        val sessions = sessionMappingMapper.selectByInstanceId(newInstanceId)
        for (mapping in sessions) {
            sessionCache.put(mapping.sessionId, newInstanceId)
        }

        log.info("Rebind $count sessions from $oldInstanceId to $newInstanceId")
        return count
    }

    override fun unbindInstanceSessions(instanceId: String): Int {
        val sessions = sessionMappingMapper.selectByInstanceId(instanceId)
        val count = sessionMappingMapper.deleteByInstanceId(instanceId)

        for (mapping in sessions) {
            sessionCache.invalidate(mapping.sessionId)
        }

        log.info("Unbound $count sessions from instance $instanceId")
        return count
    }

    private fun selectLeastLoadedInstance(instances: List<AgentInstance>): AgentInstance {
        if (instances.size == 1) return instances.first()

        val instanceIds = instances.map { it.instanceId }
        val countResults = sessionMappingMapper.countSessionsByInstances(instanceIds)

        val countMap = mutableMapOf<String, Int>()
        for (row in countResults) {
            val id = (row["instance_id"] ?: row["instanceId"]) as? String ?: continue
            val cnt = (row["cnt"] as? Number)?.toInt() ?: 0
            countMap[id] = cnt
        }

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

    fun getCacheStats(): Map<String, Any> {
        val stats = sessionCache.stats()
        return mapOf(
            "hitRate" to String.format("%.2f", stats.hitRate()),
            "missRate" to String.format("%.2f", stats.missRate()),
            "hitCount" to stats.hitCount(),
            "missCount" to stats.missCount(),
            "size" to sessionCache.estimatedSize(),
        )
    }
}
