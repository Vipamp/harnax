package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Pure in-memory session mapping service for single-node deployment.
 * No MySQL dependency - all state is held in ConcurrentHashMap.
 */
class CaffeineSessionMappingService(
    private val instanceRegistry: InstanceRegistry,
    private val heartbeatTimeoutMs: Long = 30000L,
) : SessionMappingService {

    private val log = LoggerFactory.getLogger(CaffeineSessionMappingService::class.java)

    private data class SessionBinding(
        val instanceId: String,
        val agentId: Long?,
        val lastActiveTime: LocalDateTime,
    )

    // sessionId -> binding
    private val bindings = ConcurrentHashMap<String, SessionBinding>()

    // instanceId -> set of sessionIds (reverse index)
    private val instanceSessions = ConcurrentHashMap<String, MutableSet<String>>()

    private val rerouteLocks = ConcurrentHashMap<String, ReentrantLock>()

    override fun bindSession(sessionId: String, instanceId: String, agentId: Long?) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
        require(instanceId.isNotBlank() && instanceId.length <= 64) { "Invalid instance ID" }

        val binding = SessionBinding(instanceId, agentId, LocalDateTime.now())
        bindings[sessionId] = binding

        instanceSessions.computeIfAbsent(instanceId) { ConcurrentHashMap.newKeySet() }.add(sessionId)

        log.debug("Bound session: $sessionId -> $instanceId")
    }

    override fun getInstanceId(sessionId: String): String? {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
        return bindings[sessionId]?.instanceId
    }

    override fun unbindSession(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        val binding = bindings.remove(sessionId)
        if (binding != null) {
            instanceSessions[binding.instanceId]?.remove(sessionId)
        }
        log.debug("Unbound session: $sessionId")
    }

    override fun refreshActiveTime(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        val binding = bindings[sessionId]
        if (binding != null) {
            bindings[sessionId] = binding.copy(lastActiveTime = LocalDateTime.now())
        }
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
                try {
                    val existingInstance = instanceRegistry.getInstance(existingInstanceId)
                    if (existingInstance != null &&
                        existingInstance.isHealthy(heartbeatTimeoutMs) &&
                        !existingInstance.isDraining()
                    ) {
                        log.info("Returning existing instance for session $sessionId (lock contention)")
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
            lock.unlock()
            rerouteLocks.remove(sessionId, lock)
        }
    }

    override fun rebindAllSessions(oldInstanceId: String, newInstanceId: String): Int {
        val sessions = instanceSessions[oldInstanceId]?.toSet() ?: emptySet()
        if (sessions.isEmpty()) return 0

        for (sessionId in sessions) {
            val binding = bindings[sessionId]
            if (binding != null && binding.instanceId == oldInstanceId) {
                bindings[sessionId] = binding.copy(instanceId = newInstanceId)
                instanceSessions[oldInstanceId]?.remove(sessionId)
                instanceSessions.computeIfAbsent(newInstanceId) { ConcurrentHashMap.newKeySet() }.add(sessionId)
            }
        }

        log.info("Rebind ${sessions.size} sessions from $oldInstanceId to $newInstanceId")
        return sessions.size
    }

    override fun unbindInstanceSessions(instanceId: String): Int {
        val sessions = instanceSessions.remove(instanceId)?.toSet() ?: emptySet()
        for (sessionId in sessions) {
            bindings.remove(sessionId)
        }
        log.info("Unbound ${sessions.size} sessions from instance $instanceId")
        return sessions.size
    }

    override fun getSessionCountByInstance(instanceId: String): Int =
        instanceSessions[instanceId]?.size ?: 0

    override fun getSessionCountsByInstances(instanceIds: List<String>): Map<String, Int> =
        instanceIds.associateWith { getSessionCountByInstance(it) }

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
