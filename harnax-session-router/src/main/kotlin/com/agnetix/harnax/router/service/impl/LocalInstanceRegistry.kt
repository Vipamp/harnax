package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure in-memory instance registry for single-node deployment.
 * No MySQL dependency - all state is held in ConcurrentHashMap.
 *
 * Kept semantically aligned with [RedisInstanceRegistry]: a heartbeat proves liveness but never
 * undoes a drain, and marking an instance DOWN keeps the registration so the instance can come
 * back on its next heartbeat. Transitions use [ConcurrentHashMap.compute] because
 * [com.agnetix.harnax.router.health.HeartbeatHealthChecker], the proxy failover path and the
 * monitor API all mutate the same entries from different threads.
 */
class LocalInstanceRegistry(
    private val heartbeatTimeoutMs: Long,
) : InstanceRegistry {

    private val log = LoggerFactory.getLogger(LocalInstanceRegistry::class.java)

    private val instances = ConcurrentHashMap<String, AgentInstance>()

    override fun registerInstance(instanceId: String, host: String, port: Int) {
        val previous = instances[instanceId]
        if (previous != null && (previous.host != host || previous.port != port)) {
            log.warn(
                "Instance {} re-registered at {}:{} (was {}:{})",
                instanceId,
                host,
                port,
                previous.host,
                previous.port,
            )
        }
        instances[instanceId] = AgentInstance().apply {
            this.instanceId = instanceId
            this.host = host
            this.port = port
            this.status = AgentInstance.STATUS_UP
            this.lastHeartbeat = Instant.now()
            this.active = 1
        }
        log.info("Registered instance: $instanceId at $host:$port")
    }

    override fun unregisterInstance(instanceId: String) {
        instances.remove(instanceId)
        log.info("Unregistered instance: $instanceId")
    }

    override fun refreshHeartbeat(instanceId: String): Boolean {
        var accepted = false
        instances.computeIfPresent(instanceId) { _, instance ->
            accepted = true
            instance.apply {
                lastHeartbeat = Instant.now()
                if (status != AgentInstance.STATUS_DRAINING) {
                    status = AgentInstance.STATUS_UP
                }
            }
        }
        if (!accepted) {
            log.warn("Heartbeat refresh failed - instance not found: $instanceId")
        }
        return accepted
    }

    override fun getHealthyInstances(): List<AgentInstance> = instances.values.filter { it.isAcceptingNewSessions(heartbeatTimeoutMs) }

    override fun getAllActiveInstances(): List<AgentInstance> = instances.values.filter { it.active == 1 }

    override fun getInstance(instanceId: String): AgentInstance? = instances[instanceId]?.takeIf { it.active == 1 }

    override fun markInstanceDown(instanceId: String): Int {
        var transitioned = false
        instances.computeIfPresent(instanceId) { _, instance ->
            if (instance.status == AgentInstance.STATUS_DOWN) {
                return@computeIfPresent instance
            }
            transitioned = true
            instance.apply { status = AgentInstance.STATUS_DOWN }
        }
        if (transitioned) {
            log.warn("Marked instance as DOWN: $instanceId")
            return 1
        }
        return 0
    }

    override fun markAsDraining(instanceId: String): Boolean {
        var marked = false
        instances.computeIfPresent(instanceId) { _, instance ->
            marked = true
            instance.apply {
                status = AgentInstance.STATUS_DRAINING
                lastHeartbeat = Instant.now()
            }
        }
        if (!marked) {
            log.warn("Drain ignored - instance not registered: $instanceId")
            return false
        }
        log.info("Marked instance as DRAINING: $instanceId")
        return true
    }
}
