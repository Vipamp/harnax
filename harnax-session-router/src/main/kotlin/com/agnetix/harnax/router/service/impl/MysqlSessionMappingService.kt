package com.agnetix.harnax.router.service.impl

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.mapper.SessionMappingMapper
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

class MysqlSessionMappingService(
    private val sessionMappingMapper: SessionMappingMapper,
    private val instanceRegistry: InstanceRegistry,
) : SessionMappingService {

    private val log = LoggerFactory.getLogger(MysqlSessionMappingService::class.java)

    override fun bindSession(sessionId: String, instanceId: String, agentId: Long?) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
        require(instanceId.isNotBlank() && instanceId.length <= 64) { "Invalid instance ID" }
        sessionMappingMapper.upsertBinding(sessionId, instanceId, agentId, LocalDateTime.now())
        log.debug("Bound session: $sessionId -> $instanceId")
    }

    override fun getInstanceId(sessionId: String): String? {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
        val mapping = sessionMappingMapper.selectBySessionId(sessionId)
        return mapping?.instanceId
    }

    override fun unbindSession(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
        sessionMappingMapper.deleteBySessionId(sessionId)
        log.debug("Unbound session: $sessionId")
    }

    override fun refreshActiveTime(sessionId: String) {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
        sessionMappingMapper.refreshActiveTime(sessionId, LocalDateTime.now())
    }

    override fun rerouteSession(sessionId: String): String {
        require(sessionId.isNotBlank() && sessionId.length <= 128) { "Invalid session ID" }
        val healthyInstances = instanceRegistry.getHealthyInstances()
        if (healthyInstances.isEmpty()) {
            throw IllegalStateException("No healthy agent-service instances available")
        }

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

    override fun unbindInstanceSessions(instanceId: String): Int {
        val count = sessionMappingMapper.deleteByInstanceId(instanceId)
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

        return instances.minByOrNull { inst ->
            countMap[inst.instanceId] ?: 0
        } ?: instances.first()
    }
}
