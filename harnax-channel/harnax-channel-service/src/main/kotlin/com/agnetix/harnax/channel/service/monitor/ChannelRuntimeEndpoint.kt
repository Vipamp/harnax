package com.agnetix.harnax.channel.service.monitor

import com.agnetix.harnax.channel.service.bootstrap.ChannelListenerLockGuard
import com.agnetix.harnax.channel.service.client.RouterCircuitBreaker
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only runtime view of every channel listener, exposed at `/actuator/channels`.
 *
 * Lives under `/actuator` rather than under the `/api/channel` tree on purpose: the service sits behind
 * `UnifiedAuthFilter`, so a plain REST endpoint here would need a service token to read, which
 * rules out pointing a dashboard or a `curl` at it during an incident. The actuator path prefix
 * is already exempt from that filter, and this payload carries no credentials — only ids, names,
 * connection states and counters.
 *
 * The point of this view is the delta it makes visible: "started" used to be the last thing
 * anyone knew about a channel, which is exactly when a silently dropped WebSocket becomes
 * invisible. Here, `missingListener=true` or a stale `lastActivityAt` says it out loud.
 */
@Component
@Endpoint(id = "channels")
class ChannelRuntimeEndpoint(
    private val monitor: ChannelRuntimeMonitor,
    private val routerBreaker: RouterCircuitBreaker,
    private val lockGuard: ChannelListenerLockGuard,
) {

    @ReadOperation
    fun channels(): Map<String, Any?> {
        val views = monitor.snapshot()
        return mapOf(
            "generatedAt" to Instant.now().toString(),
            // When `held` is false this instance deliberately idles; another replica owns the listeners.
            "listenerLock" to mapOf(
                "held" to lockGuard.isHeld(),
                "name" to lockGuard.lockName,
                "enabled" to lockGuard.lockEnabled,
            ),
            "summary" to mapOf(
                "total" to views.size,
                "serving" to views.count { it.serving },
                "byStatus" to views.groupingBy { it.status.name }.eachCount(),
            ),
            // First thing to check when every channel errors at once.
            "routerCircuit" to routerBreaker.snapshot(),
            "channels" to views.map { it.toPayload() },
        )
    }

    private fun ChannelRuntimeMonitor.ChannelRuntimeView.toPayload(): Map<String, Any?> = buildMap {
        put("channelId", channelId)
        put("name", name)
        put("type", type)
        put("communicationMode", communicationMode)
        put("status", status.name)
        put("serving", serving)
        put("missingListener", missingListener)
        put("statusAgeMs", statusAgeMs)
        put("lastConnectedAt", format(lastConnectedAgoMs))
        put("lastActivityAt", format(lastActivityAgoMs))
        put("reconnectCount", reconnectCount)
        put("receivedCount", receivedCount)
        put("lastError", lastError)
    }

    private fun format(agoMs: Long): String? {
        if (agoMs < 0) return null
        return DateTimeFormatter
            .ISO_LOCAL_DATE_TIME
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(System.currentTimeMillis() - agoMs))
    }
}
