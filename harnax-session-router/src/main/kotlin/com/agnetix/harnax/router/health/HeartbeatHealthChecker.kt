package com.agnetix.harnax.router.health

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Heartbeat health checker.
 * Periodically checks agent-service instances for heartbeat timeouts
 * and triggers failover for sessions bound to down instances.
 *
 * IMPORTANT: Must query ALL active instances (not just healthy ones)
 * to detect instances that are marked UP but have timed out on heartbeat.
 */
@Component
class HeartbeatHealthChecker(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
    @Value($$"${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
) {

    private val log = LoggerFactory.getLogger(HeartbeatHealthChecker::class.java)

    /**
     * Check all instances for heartbeat timeouts.
     * Runs every 5 seconds (configurable).
     *
     * Queries ALL active instances so it can detect ones that are
     * still marked as UP but whose heartbeat has expired.
     */
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

    /**
     * Handle an instance going down.
     * Mark it as DOWN and rebind its sessions to healthy instances.
     */
    private fun handleInstanceDown(downInstanceId: String, healthyInstances: List<AgentInstance>) {
        // Mark instance as DOWN
        instanceRegistry.markInstanceDown(downInstanceId)

        if (healthyInstances.isEmpty()) {
            log.error("No healthy instances available for failover! Sessions bound to $downInstanceId will be stuck.")
            return
        }

        // Rebind all sessions to the least loaded healthy instance
        val targetInstance = healthyInstances.minByOrNull {
            it.instanceId.hashCode()
        } ?: healthyInstances.first()

        val rebinding = sessionMappingService.rebindAllSessions(downInstanceId, targetInstance.instanceId)
        log.info("Failover: moved $rebinding sessions from $downInstanceId to ${targetInstance.instanceId}")
    }
}
