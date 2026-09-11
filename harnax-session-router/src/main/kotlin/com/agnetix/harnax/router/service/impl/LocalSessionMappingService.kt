package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Pure in-memory session mapping service for single-node deployment.
 * No MySQL dependency - all state is held in ConcurrentHashMap.
 *
 * Mirrors the semantics of [RedisSessionMappingService] so local and cluster mode route the same
 * way: a binding is only replaced when the caller expected the value that was there, the reverse
 * index is updated in the same step, and a binding a cluster would have let expire is dropped here
 * too — see [cleanupStaleState].
 */
open class LocalSessionMappingService(
    private val instanceRegistry: InstanceRegistry,
    private val heartbeatTimeoutMs: Long = 30000L,
    private val bindingTtl: Duration = RedisSessionMappingService.SESSION_TTL,
) : SessionMappingService {

    private val log = LoggerFactory.getLogger(LocalSessionMappingService::class.java)

    private data class SessionBinding(
        val instanceId: String,
        val agentId: Long?,
        val lastActiveTime: Instant,
    )

    // sessionId -> binding
    private val bindings = ConcurrentHashMap<String, SessionBinding>()

    // instanceId -> set of sessionIds (reverse index)
    private val instanceSessions = ConcurrentHashMap<String, MutableSet<String>>()

    private val rerouteLocks = ConcurrentHashMap<String, ReentrantLock>()

    override fun bindSession(sessionId: String, instanceId: String, agentId: Long?) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
        require(instanceId.isNotBlank() && instanceId.length <= 64) { "Invalid instance ID" }

        var previous: SessionBinding? = null
        bindings.compute(sessionId) { _, current ->
            previous = current
            if (current?.instanceId == instanceId) {
                current.copy(agentId = agentId ?: current.agentId, lastActiveTime = Instant.now())
            } else {
                SessionBinding(instanceId, agentId, Instant.now())
            }
        }

        val previousInstanceId = previous?.instanceId
        if (previousInstanceId != null && previousInstanceId != instanceId) {
            instanceSessions[previousInstanceId]?.remove(sessionId)
        }
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

        bindings.computeIfPresent(sessionId) { _, binding -> binding.copy(lastActiveTime = Instant.now()) }
    }

    override fun rerouteSession(sessionId: String, excludeInstanceIds: Set<String>): String {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }

        val lock = rerouteLocks.computeIfAbsent(sessionId) { ReentrantLock() }
        val locked = try {
            lock.tryLock(3, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!locked) {
            // Another thread is placing this session right now; its placement is as good as ours,
            // so wait for the result instead of failing the caller's request.
            val existingInstanceId = getInstanceId(sessionId)
            if (existingInstanceId != null &&
                existingInstanceId !in excludeInstanceIds &&
                isAccepting(existingInstanceId)
            ) {
                log.info("Returning existing instance for session $sessionId (lock contention)")
                return existingInstanceId
            }
            throw IllegalStateException("Concurrent reroute in progress for session: $sessionId")
        }

        try {
            val current = bindings[sessionId]?.instanceId
            if (current != null && current !in excludeInstanceIds && isAccepting(current)) {
                refreshActiveTime(sessionId)
                return current
            }

            val candidates = instanceRegistry.getHealthyInstances().filter { it.instanceId !in excludeInstanceIds }
            if (candidates.isEmpty()) {
                val bound = current?.let { instanceRegistry.getInstance(it) }
                if (bound != null && bound.isAcceptingNewSessions(heartbeatTimeoutMs)) {
                    return bound.instanceId
                }
                throw IllegalStateException("No healthy agent-service instances available for session $sessionId")
            }

            val newInstance = selectLeastLoadedInstance(candidates)
            // Only move the session if it still has the binding we decided on; a concurrent
            // placement must not be overwritten.
            if (!moveSession(sessionId, current, newInstance.instanceId)) {
                val winner = bindings[sessionId]?.instanceId
                if (winner != null && winner !in excludeInstanceIds) return winner
            }

            log.info("Rerouted session $sessionId to instance ${newInstance.instanceId}")
            return newInstance.instanceId
        } finally {
            lock.unlock()
            rerouteLocks.remove(sessionId, lock)
        }
    }

    override fun rebindAllSessions(oldInstanceId: String, newInstanceId: String): Int {
        if (oldInstanceId == newInstanceId) {
            log.warn("rebindAllSessions called with source == target ($oldInstanceId); nothing to do")
            return 0
        }

        val sessions = instanceSessions[oldInstanceId]?.toSet() ?: emptySet()
        var moved = 0
        for (sessionId in sessions) {
            if (moveSession(sessionId, oldInstanceId, newInstanceId)) moved++
        }

        log.info("Rebind moved $moved of ${sessions.size} sessions from $oldInstanceId to $newInstanceId")
        return moved
    }

    override fun unbindInstanceSessions(instanceId: String): Int {
        val sessions = instanceSessions.remove(instanceId) ?: return 0
        var unbound = 0
        for (sessionId in sessions) {
            // remove(key, value) only drops a binding that still points at this instance.
            val binding = bindings[sessionId]
            if (binding != null && binding.instanceId == instanceId && bindings.remove(sessionId, binding)) {
                unbound++
            }
        }
        log.info("Unbound $unbound of ${sessions.size} sessions from instance $instanceId")
        return unbound
    }

    override fun getSessionCountByInstance(instanceId: String): Int = instanceSessions[instanceId]?.size ?: 0

    override fun getSessionCountsByInstances(instanceIds: List<String>): Map<String, Int> = instanceIds.associateWith { getSessionCountByInstance(it) }

    private fun isAccepting(instanceId: String): Boolean = instanceRegistry.getInstance(instanceId)?.isAcceptingNewSessions(heartbeatTimeoutMs) == true

    /**
     * Move [sessionId] to [newInstanceId] unless it is no longer bound to [expectedInstanceId]
     * (null meaning "unbound"). Keeps the binding and the reverse index in step.
     *
     * @return true if this call performed the move.
     */
    private fun moveSession(
        sessionId: String,
        expectedInstanceId: String?,
        newInstanceId: String,
    ): Boolean {
        var moved = false
        bindings.compute(sessionId) { _, current ->
            if (current?.instanceId == expectedInstanceId) {
                moved = true
                SessionBinding(newInstanceId, current?.agentId, Instant.now())
            } else {
                current
            }
        }

        if (moved) {
            if (expectedInstanceId != null) {
                instanceSessions[expectedInstanceId]?.remove(sessionId)
            }
            instanceSessions.computeIfAbsent(newInstanceId) { ConcurrentHashMap.newKeySet() }.add(sessionId)
        }
        return moved
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

    /**
     * In-memory state has no TTL to lean on: without a sweep, every session that was never explicitly
     * closed sits in this map for the life of the process. Drop the bindings that have been idle as
     * long as cluster mode would have let them expire, and with them the reroute locks a killed thread
     * left behind — the normal path removes those itself.
     */
    @Scheduled(fixedDelay = 60_000)
    open fun cleanupStaleState() {
        var locks = 0
        rerouteLocks.forEach { (sessionId, lock) ->
            if (!lock.isLocked && rerouteLocks.remove(sessionId, lock)) locks++
        }

        val cutoff = Instant.now().minus(bindingTtl)
        var expired = 0
        bindings.forEach { (sessionId, binding) ->
            // The two-argument remove only drops the binding this sweep looked at; a session that
            // moved in the meantime keeps its fresh one.
            if (binding.lastActiveTime.isBefore(cutoff) && bindings.remove(sessionId, binding)) {
                instanceSessions[binding.instanceId]?.remove(sessionId)
                expired++
            }
        }

        if (expired > 0 || locks > 0) {
            log.info("Local routing state cleaned: {} idle bindings dropped, {} stale locks released", expired, locks)
        }
    }
}
