package com.agnetix.harnax.router.health

import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceCircuitBreaker
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class HeartbeatHealthChecker(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
    private val circuitBreaker: InstanceCircuitBreaker,
    private val meterRegistry: MeterRegistry,
    @Value("\${router.health.heartbeat-timeout-ms:30000}")
    private val heartbeatTimeoutMs: Long,
) {

    private val log = LoggerFactory.getLogger(HeartbeatHealthChecker::class.java)

    private val recentFailovers = ConcurrentHashMap<String, Long>()
    private val failoverCooldownMs = 10000L

    // Entries older than this are considered stale and eligible for cleanup.
    private val failoverEntryTtlMs = 300_000L // 5 minutes

    /**
     * How many instances this router could place a new session on right now, excluding DRAINING
     * ones. Scrape-time read rather than a counter bumped by the health loop: the two would drift
     * on every path that changes fleet membership outside this node (register, drain, another
     * router marking it DOWN).
     */
    @PostConstruct
    fun registerGauges() {
        Gauge.builder("router.healthy.instances", instanceRegistry) {
            it.getHealthyInstances().size.toDouble()
        }.register(meterRegistry)
    }

    @Scheduled(fixedDelayString = "\${router.health.check-interval-ms:5000}")
    fun checkInstanceHealth() {
        val allActiveInstances = instanceRegistry.getAllActiveInstances()
        val downInstances = allActiveInstances.filter { !it.isHealthy(heartbeatTimeoutMs) }

        if (downInstances.isNotEmpty()) {
            log.warn("Detected ${downInstances.size} unhealthy instances: ${downInstances.map { it.instanceId }}")

            for (downInstance in downInstances) {
                // Re-fetch healthy instances for each down instance to avoid stale snapshot:
                // if multiple instances go down in the same cycle, sessions from the first
                // should not be rebound to the second (which is also down).
                // DRAINING instances are alive but must not receive migrated sessions, so ask the
                // registry for "accepting new sessions" rather than filtering on isHealthy.
                val currentHealthy = instanceRegistry.getHealthyInstances()
                handleInstanceDown(downInstance.instanceId, currentHealthy)
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

        // A migration is a bulk new placement, so it must not land on an instance whose request
        // path is already failing. If every candidate is tripped, migrate anyway: sessions stuck
        // behind a dead instance are worse than a batch that may have to move again.
        val targets = healthyInstances.filterNot { circuitBreaker.isOpen(it.instanceId) }
            .ifEmpty { healthyInstances }

        val targetInstance = selectFailoverTarget(targets, downInstanceId)
        val countMap = sessionMappingService.getSessionCountsByInstances(targets.map { it.instanceId })
        val currentLoad = countMap[targetInstance.instanceId] ?: 0
        val avgLoad = countMap.values.sum().toDouble() / targets.size

        if (currentLoad > avgLoad * 2) {
            log.warn("Target instance ${targetInstance.instanceId} is overloaded ($currentLoad sessions, avg: $avgLoad), selecting alternative")
            val alternativeTargets = targets.filter {
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

    /**
     * Periodically clean up stale entries from [recentFailovers] to prevent
     * unbounded growth when instances are permanently removed.
     */
    @Scheduled(fixedDelay = 60_000)
    fun cleanupStaleFailovers() {
        val now = System.currentTimeMillis()
        val staleEntries = recentFailovers.entries.filter { now - it.value > failoverEntryTtlMs }.map { it.key }
        for (key in staleEntries) {
            recentFailovers.remove(key)
        }
        if (staleEntries.isNotEmpty()) {
            log.debug("Cleaned up {} stale failover entries", staleEntries.size)
        }
    }

    private fun selectFailoverTarget(healthyInstances: List<AgentInstance>, excludeInstanceId: String): AgentInstance {
        val candidates = healthyInstances.filter { it.instanceId != excludeInstanceId }
            .ifEmpty { healthyInstances }
        // Pick the least-loaded instance by session count to avoid concentrating load on one node.
        val countMap = sessionMappingService.getSessionCountsByInstances(candidates.map { it.instanceId })
        return candidates.minByOrNull { countMap[it.instanceId] ?: 0 } ?: candidates.first()
    }
}
