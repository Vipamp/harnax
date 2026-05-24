package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.mapper.AgentInstanceMapper
import com.agnetix.harnax.router.service.InstanceRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * MySQL-backed implementation of InstanceRegistry.
 * Stores instance information and health status in the database.
 */
@Service
class MysqlInstanceRegistry(
    private val agentInstanceMapper: AgentInstanceMapper,
    @Value("\${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
) : InstanceRegistry {

    private val log = LoggerFactory.getLogger(MysqlInstanceRegistry::class.java)

    override fun registerInstance(instanceId: String, host: String, port: Int) {
        val existing = agentInstanceMapper.selectByInstanceId(instanceId)
        if (existing != null) {
            // Update existing instance
            existing.host = host
            existing.port = port
            existing.status = "UP"
            existing.lastHeartbeat = LocalDateTime.now()
            existing.active = 1
            agentInstanceMapper.updateHeartbeat(instanceId, LocalDateTime.now(), "UP")
            log.info("Updated existing instance registration: $instanceId at $host:$port")
        } else {
            // Create new instance
            val instance = AgentInstance()
            instance.instanceId = instanceId
            instance.host = host
            instance.port = port
            instance.status = "UP"
            instance.lastHeartbeat = LocalDateTime.now()
            instance.active = 1
            agentInstanceMapper.insert(instance)
            log.info("Registered new instance: $instanceId at $host:$port")
        }
    }

    override fun unregisterInstance(instanceId: String) {
        agentInstanceMapper.deleteByInstanceId(instanceId)
        log.info("Unregistered instance: $instanceId")
    }

    override fun refreshHeartbeat(instanceId: String) {
        val rows = agentInstanceMapper.updateHeartbeat(instanceId, LocalDateTime.now(), "UP")
        if (rows == 0) {
            log.warn("Heartbeat refresh failed - instance not found: $instanceId")
        }
    }

    override fun getHealthyInstances(): List<AgentInstance> {
        // Get all instances marked as UP, then filter by heartbeat timeout
        val allUp = agentInstanceMapper.selectHealthyInstances()
        val healthy = allUp.filter { it.isHealthy(heartbeatTimeoutMs) }
        log.debug("Found ${healthy.size} healthy instances out of ${allUp.size} UP instances")
        return healthy
    }

    override fun getAllActiveInstances(): List<AgentInstance> = agentInstanceMapper.selectAllInstances()

    override fun getInstance(instanceId: String): AgentInstance? = agentInstanceMapper.selectByInstanceId(instanceId)

    override fun markInstanceDown(instanceId: String) {
        agentInstanceMapper.markAsDown(instanceId)
        log.warn("Marked instance as DOWN: $instanceId")
    }
}
