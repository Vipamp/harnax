package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.SessionMapping
import com.agnetix.harnax.router.mapper.SessionMappingMapper
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * MySQL-backed implementation of SessionMappingService.
 * Stores session-to-instance mappings in the database.
 */
@Service
class MysqlSessionMappingService(
    private val sessionMappingMapper: SessionMappingMapper,
    private val instanceRegistry: InstanceRegistry,
) : SessionMappingService {

    private val log = LoggerFactory.getLogger(MysqlSessionMappingService::class.java)

    override fun bindSession(sessionId: String, instanceId: String, agentId: Long?) {
        val existing = sessionMappingMapper.selectBySessionId(sessionId)
        if (existing != null) {
            // Update existing binding
            sessionMappingMapper.updateBinding(sessionId, instanceId, LocalDateTime.now())
            log.debug("Updated session binding: $sessionId -> $instanceId")
        } else {
            // Create new binding
            val mapping = SessionMapping()
            mapping.sessionId = sessionId
            mapping.instanceId = instanceId
            mapping.agentId = agentId
            mapping.lastActiveTime = LocalDateTime.now()
            mapping.active = 1
            sessionMappingMapper.insert(mapping)
            log.debug("Created session binding: $sessionId -> $instanceId")
        }
    }

    override fun getInstanceId(sessionId: String): String? {
        val mapping = sessionMappingMapper.selectBySessionId(sessionId)
        return mapping?.instanceId
    }

    override fun unbindSession(sessionId: String) {
        sessionMappingMapper.deleteBySessionId(sessionId)
        log.debug("Unbound session: $sessionId")
    }

    override fun refreshActiveTime(sessionId: String) {
        sessionMappingMapper.refreshActiveTime(sessionId, LocalDateTime.now())
    }

    override fun rerouteSession(sessionId: String): String {
        val healthyInstances = instanceRegistry.getHealthyInstances()
        if (healthyInstances.isEmpty()) {
            throw IllegalStateException("No healthy agent-service instances available")
        }

        // Select the instance with the least sessions (simple load balancing)
        val newInstance = selectLeastLoadedInstance(healthyInstances)
        bindSession(sessionId, newInstance.instanceId)
        log.info("Rerouted session $sessionId to instance ${newInstance.instanceId}")
        return newInstance.instanceId
    }

    override fun rebindAllSessions(oldInstanceId: String, newInstanceId: String): Int {
        val count = sessionMappingMapper.rebindSessions(oldInstanceId, newInstanceId)
        log.info("Rebind $count sessions from $oldInstanceId to $newInstanceId")
        return count
    }

    /**
     * Select the instance with the least number of sessions.
     */
    private fun selectLeastLoadedInstance(instances: List<com.agnetix.harnax.router.entity.AgentInstance>): com.agnetix.harnax.router.entity.AgentInstance = instances.minByOrNull { inst ->
        sessionMappingMapper.selectByInstanceId(inst.instanceId).size
    } ?: instances.first()
}
