package com.agnetix.harnax.router.health

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.mapper.AgentInstanceMapper
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

@Component
class HeartbeatHealthChecker(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
    private val agentInstanceMapper: AgentInstanceMapper,
    @Value($$"${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
    @Value($$"${router.cleanup.retention-days:7}")
    private val retentionDays: Int,
) {

    private val log = LoggerFactory.getLogger(HeartbeatHealthChecker::class.java)

    // Track failover operations to prevent storm
    private val recentFailovers = ConcurrentHashMap<String, Long>()
    private val failoverCooldownMs = 10000L // 10 seconds cooldown per instance

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
        log.info("Cleanup: purged $purgedInstances instances older than $retentionDays days")
    }

    private fun handleInstanceDown(downInstanceId: String, healthyInstances: List<AgentInstance>) {
        // Rate limiting: check if we recently performed failover for this instance
        val now = System.currentTimeMillis()
        val lastFailoverTime = recentFailovers[downInstanceId] ?: 0
        if (now - lastFailoverTime < failoverCooldownMs) {
            log.debug("Skipping failover for $downInstanceId - in cooldown period (${now - lastFailoverTime}ms < ${failoverCooldownMs}ms)")
            return
        }

        val rows = instanceRegistry.markInstanceDown(downInstanceId)
        if (rows == 0) {
            log.debug("Instance $downInstanceId already marked DOWN by another router, skipping failover")
            return
        }

        if (healthyInstances.isEmpty()) {
            log.error("No healthy instances available for failover! Sessions bound to $downInstanceId will be stuck.")
            recentFailovers[downInstanceId] = now
            return
        }

        val targetInstance = selectFailoverTarget(healthyInstances, downInstanceId)

        // Verify target instance is not overloaded before rebinding
        val currentLoad = getSessionCount(targetInstance.instanceId)
        val avgLoad = getAverageSessionLoad(healthyInstances)
        
        if (currentLoad > avgLoad * 2) {
            log.warn("Target instance ${targetInstance.instanceId} is overloaded ($currentLoad sessions, avg: $avgLoad), selecting alternative")
            val alternativeTargets = healthyInstances.filter { 
                it.instanceId != targetInstance.instanceId && 
                getSessionCount(it.instanceId) <= avgLoad * 1.5 
            }
            if (alternativeTargets.isNotEmpty()) {
                val betterTarget = alternativeTargets.minByOrNull { getSessionCount(it.instanceId) }!!
                log.info("Selected less loaded instance ${betterTarget.instanceId} (${getSessionCount(betterTarget.instanceId)} sessions)")
                rebindSessions(downInstanceId, betterTarget)
                recentFailovers[downInstanceId] = now
                return
            }
        }

        rebindSessions(downInstanceId, targetInstance)
        recentFailovers[downInstanceId] = now
    }

    private fun rebindSessions(downInstanceId: String, targetInstance: AgentInstance) {
        val rebinding = sessionMappingService.rebindAllSessions(downInstanceId, targetInstance.instanceId)
        log.info("Failover: moved $rebinding sessions from $downInstanceId to ${targetInstance.instanceId}")
    }

    private fun getSessionCount(instanceId: String): Int {
        // This would need to be added to SessionMappingMapper or use a simpler heuristic
        return 0 // Placeholder - in production, query actual session count
    }

    private fun getAverageSessionLoad(instances: List<AgentInstance>): Double {
        if (instances.isEmpty()) return 0.0
        val totalSessions = instances.sumOf { getSessionCount(it.instanceId) }
        return totalSessions.toDouble() / instances.size
    }

    private fun selectFailoverTarget(healthyInstances: List<AgentInstance>, excludeInstanceId: String): AgentInstance {
        val candidates = healthyInstances.filter { it.instanceId != excludeInstanceId }
            .ifEmpty { healthyInstances }
        return candidates.minByOrNull { it.instanceId } ?: candidates.first()
    }
}
