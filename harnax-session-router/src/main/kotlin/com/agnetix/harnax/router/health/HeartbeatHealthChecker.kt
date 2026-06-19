package com.agnetix.harnax.router.health

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class HeartbeatHealthChecker(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
    @Value("\${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
) {

    private val log = LoggerFactory.getLogger(HeartbeatHealthChecker::class.java)

    private val recentFailovers = ConcurrentHashMap<String, Long>()
    private val failoverCooldownMs = 10000L

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

    private fun handleInstanceDown(downInstanceId: String, healthyInstances: List<AgentInstance>) {
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
        val countMap = sessionMappingService.getSessionCountsByInstances(healthyInstances.map { it.instanceId })
        val currentLoad = countMap[targetInstance.instanceId] ?: 0
        val avgLoad = if (healthyInstances.isEmpty()) 0.0 else countMap.values.sum().toDouble() / healthyInstances.size

        if (currentLoad > avgLoad * 2) {
            log.warn("Target instance ${targetInstance.instanceId} is overloaded ($currentLoad sessions, avg: $avgLoad), selecting alternative")
            val alternativeTargets = healthyInstances.filter {
                it.instanceId != targetInstance.instanceId &&
                    (countMap[it.instanceId] ?: 0) <= avgLoad * 1.5
            }
            if (alternativeTargets.isNotEmpty()) {
                val betterTarget = alternativeTargets.minByOrNull { countMap[it.instanceId] ?: 0 }!!
                val betterLoad = countMap[betterTarget.instanceId] ?: 0
                log.info("Selected less loaded instance ${betterTarget.instanceId} ($betterLoad sessions)")
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

    private fun selectFailoverTarget(healthyInstances: List<AgentInstance>, excludeInstanceId: String): AgentInstance {
        val candidates = healthyInstances.filter { it.instanceId != excludeInstanceId }
            .ifEmpty { healthyInstances }
        // Pick the least-loaded instance by session count to avoid concentrating load on one node.
        val countMap = sessionMappingService.getSessionCountsByInstances(candidates.map { it.instanceId })
        return candidates.minByOrNull { countMap[it.instanceId] ?: 0 } ?: candidates.first()
    }
}
