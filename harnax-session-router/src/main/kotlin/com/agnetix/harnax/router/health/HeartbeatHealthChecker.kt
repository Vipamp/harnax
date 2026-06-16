package com.agnetix.harnax.router.health

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.mapper.AgentInstanceMapper
import com.agnetix.harnax.router.mapper.SessionMappingMapper
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class HeartbeatHealthChecker(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
    private val agentInstanceMapper: AgentInstanceMapper,
    private val sessionMappingMapper: SessionMappingMapper,
    @Value($$"${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
    @Value($$"${router.cleanup.retention-days:7}")
    private val retentionDays: Int,
) {

    private val log = LoggerFactory.getLogger(HeartbeatHealthChecker::class.java)

    @Scheduled(fixedDelayString = "\${router.health.check-interval-ms:5000}")
    fun checkInstanceHealth() {
        val allActiveInstances = instanceRegistry.getAllActiveInstances()
        val healthyInstances = allActiveInstances.filter { it.isHealthy(heartbeatTimeoutMs) }
        val downInstances = allActiveInstances.filter { !it.isHealthy(heartbeatTimeoutMs) }

        if (downInstances.isNotEmpty()) {
            log.warn("Detected ${downInstances.size} unhealthy instances: ${downInstances.map { it.instanceId }}")

            for (downInstance in downInstances) {
                handleInstanceDown(downInstance.instanceId, healthyInstances)
            }
        }
    }

    @Scheduled(cron = "\${router.cleanup.cron:0 0 3 * * ?}")
    fun cleanupDeletedRecords() {
        val cutoff = LocalDateTime.now().minusDays(retentionDays.toLong())
        val purgedInstances = agentInstanceMapper.purgeDeletedInstances(cutoff)
        val purgedMappings = sessionMappingMapper.purgeDeletedMappings(cutoff)
        if (purgedInstances > 0 || purgedMappings > 0) {
            log.info("Cleanup: purged $purgedInstances instances and $purgedMappings session mappings older than $retentionDays days")
        }
    }

    private fun handleInstanceDown(downInstanceId: String, healthyInstances: List<AgentInstance>) {
        val rows = instanceRegistry.markInstanceDown(downInstanceId)
        if (rows == 0) {
            log.debug("Instance $downInstanceId already marked DOWN by another router, skipping failover")
            return
        }

        if (healthyInstances.isEmpty()) {
            log.error("No healthy instances available for failover! Sessions bound to $downInstanceId will be stuck.")
            return
        }

        val targetInstance = healthyInstances.minByOrNull {
            it.instanceId.hashCode()
        } ?: healthyInstances.first()

        val rebinding = sessionMappingService.rebindAllSessions(downInstanceId, targetInstance.instanceId)
        log.info("Failover: moved $rebinding sessions from $downInstanceId to ${targetInstance.instanceId}")
    }
}
