package com.agnetix.harnax.router.controller

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.dto.ApiCallLogPage
import com.agnetix.harnax.router.dto.ApiCallLogQuery
import com.agnetix.harnax.router.dto.InstanceInfo
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.ApiCallLogService
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Router Monitor Controller.
 *
 * Read-only observability surface intended for the bundled static UI
 * (and any external dashboard tooling). Unlike [InstanceRegistryController],
 * these endpoints are NOT marked [com.agnetix.harnax.auth.InternalOnly]
 * so they can be reached from a browser without service-to-service credentials.
 *
 * Access control is delegated to the upstream edge (nginx in production, or
 * the developer machine in local mode). Authentication/authorization for the
 * monitor UI itself is out of scope here.
 */
@RestController
@RequestMapping("/api/router/monitor")
class RouterMonitorController(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
    private val apiCallLogService: ApiCallLogService,
) {

    private val log = LoggerFactory.getLogger(RouterMonitorController::class.java)

    /**
     * List all currently registered agent-service instances with derived fields
     * (session count, time since last heartbeat) suitable for a dashboard.
     */
    @GetMapping("/instances")
    fun listInstances(): ResultVo<List<MonitorInstanceInfo>> {
        val instances = instanceRegistry.getAllActiveInstances()
        val sessionCounts: Map<String, Int> = runCatching {
            sessionMappingService.getSessionCountsByInstances(instances.map { it.instanceId })
        }.getOrElse { emptyMap() }
            .mapValues { it.value }

        val now = System.currentTimeMillis()
        val result = instances.map { inst ->
            val lastMs = parseLastHeartbeatMs(inst.lastHeartbeat)
            val ageMs = if (lastMs > 0) now - lastMs else -1L
            MonitorInstanceInfo(
                instanceId = inst.instanceId,
                host = inst.host,
                port = inst.port,
                status = inst.status,
                lastHeartbeat = inst.lastHeartbeat.toString(),
                lastHeartbeatAgeMs = ageMs,
                sessionCount = sessionCounts[inst.instanceId] ?: 0,
            )
        }.sortedBy { it.instanceId }

        return ResultVo.success(result)
    }

    /**
     * Paginated call log query with optional filters.
     * Designed for the monitor UI's "call details" panel.
     */
    @GetMapping("/call-logs")
    fun queryCallLogs(
        @RequestParam(required = false) sessionId: String?,
        @RequestParam(required = false) instanceId: String?,
        @RequestParam(required = false) agentName: String?,
        @RequestParam(required = false) statusCode: Int?,
        @RequestParam(required = false) success: Int?,
        @RequestParam(required = false) minDurationMs: Long?,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): ResultVo<ApiCallLogPage> {
        val query = ApiCallLogQuery(
            sessionId = sessionId?.takeIf { it.isNotBlank() },
            instanceId = instanceId?.takeIf { it.isNotBlank() },
            agentName = agentName?.takeIf { it.isNotBlank() },
            statusCode = statusCode,
            success = success,
            minDurationMs = minDurationMs,
            limit = limit,
            offset = offset,
        )
        log.debug("[Monitor] call-logs query: {}", query)
        return ResultVo.success(apiCallLogService.query(query))
    }

    private fun parseLastHeartbeatMs(value: Any?): Long {
        // AgentInstance.lastHeartbeat is a LocalDateTime; convert to epoch ms best-effort.
        return when (value) {
            null -> 0L
            is java.time.LocalDateTime -> value.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            is java.time.Instant -> value.toEpochMilli()
            is Number -> value.toLong()
            else -> 0L
        }
    }
}

/**
 * Enriched instance info returned to the monitor UI.
 *
 * Extends [InstanceInfo] with derived fields that the UI cares about but
 * are cheap to compute server-side (session count, last-heartbeat age).
 */
data class MonitorInstanceInfo(
    val instanceId: String,
    val host: String,
    val port: Int,
    val status: String,
    val lastHeartbeat: String,
    val lastHeartbeatAgeMs: Long,
    val sessionCount: Int,
) {
    companion object {
        /**
         * Convenience for tests / dummy data. Kept here to avoid leaking
         * AgentInstance types into the response shape.
         */
        @Suppress("unused")
        fun fromAgentInstance(inst: AgentInstance, sessionCount: Int, nowMs: Long): MonitorInstanceInfo {
            val lastMs = inst.lastHeartbeat.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            return MonitorInstanceInfo(
                instanceId = inst.instanceId,
                host = inst.host,
                port = inst.port,
                status = inst.status,
                lastHeartbeat = inst.lastHeartbeat.toString(),
                lastHeartbeatAgeMs = nowMs - lastMs,
                sessionCount = sessionCount,
            )
        }
    }
}
