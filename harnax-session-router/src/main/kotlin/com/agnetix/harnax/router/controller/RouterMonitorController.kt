package com.agnetix.harnax.router.controller

import com.agnetix.harnax.auth.AuthContextHolder
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
 * Read-only observability surface for the bundled static UI (and any external dashboard
 * tooling). Unlike [InstanceRegistryController] these endpoints are not marked
 * [com.agnetix.harnax.auth.InternalOnly]: a browser cannot hold service-to-service credentials,
 * so an operator's own JWT or API key is what they carry. That is a difference in who may call,
 * not a licence to be anonymous — node addresses and a full call trail are a map of everything
 * worth attacking, so the monitor is authenticated like the rest of the API and the edge is only
 * the second lock.
 *
 * Authentication alone does not say how much each caller is shown, so the two endpoints answer that
 * differently. [queryCallLogs] is every call *this tenant* made, and only the full trail for a caller
 * that has no tenant to begin with (see [com.agnetix.harnax.router.config.ApiCallLogFilter] for who
 * writes those rows). [listInstances] is cluster topology, which belongs to no tenant: it is the
 * operator's view, and it stops at what an instance *is* — host, port, heartbeat, how many sessions it
 * holds — rather than whose sessions they are or what any of them said.
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
     *
     * Every parameter here narrows the result; none of them decides *whose* rows are in it. That is
     * decided below, from the credential the auth filter already validated.
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
        // Confine the caller to its own tenant, derived server-side. This endpoint has never accepted a
        // `tenantId` parameter and must not start: every other argument above is a filter the caller
        // chooses, and one it chooses cannot also be the boundary it is inside — a caller that could
        // name a tenant would name the one it wanted to read.
        //
        // A caller with no tenant is an internal service token or a SYSTEM key and keeps the full view.
        // That is the same "no tenant means internal" pass-through SessionAccessGuard runs on, and the
        // router's own operator UI is built on it.
        //
        // The consequence in data terms, stated rather than hidden: rows written by those internal
        // callers carry `tenant_id IS NULL` (see ApiCallLogFilter, which stamps the tenant the caller
        // presented and has none to stamp), so a tenant caller never sees them. That is intended — an
        // unattributable row must not become everyone's — and it is not a gap to close by widening the
        // filter to `tenant_id IS NULL OR ...`.
        val callerTenant = AuthContextHolder.get()?.tenantId
        val query = ApiCallLogQuery(
            sessionId = sessionId?.takeIf { it.isNotBlank() },
            instanceId = instanceId?.takeIf { it.isNotBlank() },
            agentName = agentName?.takeIf { it.isNotBlank() },
            statusCode = statusCode,
            success = success,
            minDurationMs = minDurationMs,
            tenantId = callerTenant,
            limit = limit,
            offset = offset,
        )
        log.debug("[Monitor] call-logs query: {}", query)
        return ResultVo.success(apiCallLogService.query(query))
    }

    private fun parseLastHeartbeatMs(value: Any?): Long = // AgentInstance.lastHeartbeat is an Instant; epoch millis is the legacy Redis form.
        when (value) {
            null -> 0L
            is java.time.Instant -> value.toEpochMilli()
            is Number -> value.toLong()
            else -> 0L
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
            val lastMs = inst.lastHeartbeat.toEpochMilli()
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
