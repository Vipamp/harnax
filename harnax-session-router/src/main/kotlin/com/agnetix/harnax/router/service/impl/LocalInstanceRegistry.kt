package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure in-memory instance registry for single-node deployment.
 * No MySQL dependency - all state is held in ConcurrentHashMap.
 */
class LocalInstanceRegistry(
    private val heartbeatTimeoutMs: Long,
) : InstanceRegistry {

    private val log = LoggerFactory.getLogger(LocalInstanceRegistry::class.java)

    private val instances = ConcurrentHashMap<String, AgentInstance>()

    override fun registerInstance(instanceId: String, host: String, port: Int) {
        val instance = AgentInstance().apply {
            this.instanceId = instanceId
            this.host = host
            this.port = port
            this.status = "UP"
            this.lastHeartbeat = LocalDateTime.now()
            this.active = 1
        }
        instances[instanceId] = instance
        log.info("Registered instance: $instanceId at $host:$port")
    }

    override fun unregisterInstance(instanceId: String) {
        instances.remove(instanceId)
        log.info("Unregistered instance: $instanceId")
    }

    override fun refreshHeartbeat(instanceId: String) {
        val instance = instances[instanceId]
        if (instance != null) {
            instance.lastHeartbeat = LocalDateTime.now()
            instance.status = "UP"
            log.debug("Refreshed heartbeat for instance: $instanceId")
        } else {
            log.warn("Heartbeat refresh failed - instance not found: $instanceId")
        }
    }

    override fun getHealthyInstances(): List<AgentInstance> = instances.values.filter { it.isHealthy(heartbeatTimeoutMs) }

    override fun getAllActiveInstances(): List<AgentInstance> = instances.values.filter { it.active == 1 }

    override fun getInstance(instanceId: String): AgentInstance? = instances[instanceId]?.takeIf { it.active == 1 }

    override fun markInstanceDown(instanceId: String): Int {
        val instance = instances[instanceId]
        if (instance != null && instance.status != "DOWN") {
            instance.status = "DOWN"
            instance.active = 0
            log.warn("Marked instance as DOWN: $instanceId")
            return 1
        }
        return 0
    }

    override fun markAsDraining(instanceId: String) {
        val instance = instances[instanceId]
        if (instance != null) {
            instance.status = "DRAINING"
            instance.lastHeartbeat = LocalDateTime.now()
            log.info("Marked instance as DRAINING: $instanceId")
        }
    }
}
