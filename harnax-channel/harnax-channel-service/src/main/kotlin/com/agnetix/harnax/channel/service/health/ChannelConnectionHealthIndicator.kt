package com.agnetix.harnax.channel.service.health

import com.agnetix.harnax.channel.sdk.monitor.ChannelConnectionStatus
import com.agnetix.harnax.channel.service.monitor.ChannelRuntimeMonitor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.stereotype.Component

/**
 * Reports channel listener health to actuator under `channelConnections`.
 *
 * Deliberately kept out of the liveness probe: a Feishu socket dropped by the platform is a
 * degraded service, not a broken JVM, and restarting the process would take the other channels
 * with it. Deployment probes point at `/actuator/health/liveness`; this indicator is for humans
 * and for alerting on `/actuator/health`.
 *
 * Status rules:
 * - `UP` — every expected channel is serving, or none is expected (webhook-only setup)
 * - `OUT_OF_SERVICE` — a channel is stuck in a non-serving state but has not reported a hard failure
 * - `DOWN` — at least one channel is FAILED for longer than [failureGraceMs] (bad credentials,
 *   expired login, connect loop) — set `channel.monitor.health.enabled=false` if you would rather
 *   watch the metrics than have the aggregate status go red.
 */
@Component("channelConnections")
class ChannelConnectionHealthIndicator(
    private val monitor: ChannelRuntimeMonitor,
    @Value("\${channel.monitor.health.enabled:true}") private val enabled: Boolean,
    @Value("\${channel.monitor.health.failure-grace-ms:60000}") private val failureGraceMs: Long,
) : HealthIndicator {

    private val log = LoggerFactory.getLogger(ChannelConnectionHealthIndicator::class.java)

    override fun health(): Health {
        if (!enabled) {
            return Health.up().withDetail("enabled", false).build()
        }
        val views = monitor.snapshot()
        if (views.isEmpty()) {
            return Health.up().withDetail("channels", "none configured").build()
        }

        val failed = views.filter { it.status == ChannelConnectionStatus.FAILED && it.statusAgeMs >= failureGraceMs }
        val degraded = views.filter { !it.serving && it.status != ChannelConnectionStatus.FAILED }

        val healthStatus = when {
            failed.isNotEmpty() -> Status.DOWN
            degraded.isNotEmpty() -> Status.OUT_OF_SERVICE
            else -> Status.UP
        }
        if (healthStatus != Status.UP) {
            // Polled by monitoring systems every few seconds; the change itself is already a WARN
            // in the reconcile loop and a counter move on channel.connection.up.
            log.debug(
                "Channel health {}: failed={}, degraded={}",
                healthStatus.code,
                failed.map { "${it.channelId}:${it.name}" },
                degraded.map { "${it.channelId}:${it.name}(${it.status})" },
            )
        }

        return Health
            .status(healthStatus)
            .withDetail("total", views.size)
            .withDetail("serving", views.count { it.serving })
            .withDetail("failed", failed.map { "${it.channelId}=${it.name} (${it.lastError ?: "-"})" })
            .withDetail(
                "degraded",
                degraded.map {
                    "${it.channelId}=${it.name} [${it.status}, ${it.statusAgeMs}ms" +
                        (if (it.lastActivityAgoMs >= 0) ", idle ${it.lastActivityAgoMs}ms" else "") + "]"
                },
            ).build()
    }
}
