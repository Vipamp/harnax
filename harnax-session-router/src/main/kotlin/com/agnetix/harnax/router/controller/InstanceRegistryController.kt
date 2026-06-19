package com.agnetix.harnax.router.controller

import com.agnetix.harnax.auth.InternalOnly
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.dto.InstanceInfo
import com.agnetix.harnax.router.dto.InstanceOperationResponse
import com.agnetix.harnax.router.dto.RouterHealthResponse
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * Instance Registry Controller.
 * Manages agent-service instance registration, heartbeat, and lifecycle.
 * All instance management endpoints are restricted to internal services only.
 */
@RestController
@RequestMapping("/api/router")
@InternalOnly
class InstanceRegistryController(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
) {

    private val log = LoggerFactory.getLogger(InstanceRegistryController::class.java)

    /**
     * Register a new agent-service instance.
     * Validates host and port to prevent SSRF attacks.
     */
    @PostMapping("/instance/register")
    fun registerInstance(
        @RequestParam instanceId: String,
        @RequestParam host: String,
        @RequestParam port: Int,
    ): ResultVo<InstanceOperationResponse> {
        log.info("Instance registration request: $instanceId at $host:$port")

        // Security validation: Only allow IP addresses (no domain names)
        if (!AgentInstance.isValidIpAddress(host)) {
            log.warn("Rejected instance registration - invalid host format: $host")
            return ResultVo.error("Only IPv4 addresses are allowed for security reasons")
        }

        // Security validation: Block dangerous IP ranges
        if (AgentInstance.isBlockedHost(host)) {
            log.warn("Rejected instance registration - blocked host: $host")
            return ResultVo.error("Host address is not allowed")
        }

        // Security validation: Restrict port range
        if (!AgentInstance.isValidPort(port)) {
            log.warn("Rejected instance registration - invalid port: $port")
            return ResultVo.error("Port must be in range ${AgentInstance.MIN_PORT}-${AgentInstance.MAX_PORT}")
        }

        instanceRegistry.registerInstance(instanceId, host, port)
        return ResultVo.success(InstanceOperationResponse(status = "registered", instanceId = instanceId))
    }

    /**
     * Refresh heartbeat for an existing instance.
     */
    @PostMapping("/instance/heartbeat")
    fun heartbeat(@RequestParam instanceId: String): ResultVo<InstanceOperationResponse> {
        instanceRegistry.refreshHeartbeat(instanceId)
        return ResultVo.success(InstanceOperationResponse(status = "ok", instanceId = instanceId))
    }

    /**
     * Unregister an agent-service instance.
     */
    @PostMapping("/instance/unregister")
    fun unregisterInstance(@RequestParam instanceId: String): ResultVo<InstanceOperationResponse> {
        log.info("Instance unregistration request: $instanceId")
        instanceRegistry.unregisterInstance(instanceId)
        sessionMappingService.unbindInstanceSessions(instanceId)
        return ResultVo.success(InstanceOperationResponse(status = "unregistered", instanceId = instanceId))
    }

    /**
     * Mark an instance as draining (graceful shutdown).
     * The instance stops receiving new sessions but continues processing active requests.
     */
    @PostMapping("/instance/drain")
    fun drainInstance(@RequestParam instanceId: String): ResultVo<InstanceOperationResponse> {
        log.info("Instance drain request: $instanceId")
        instanceRegistry.markAsDraining(instanceId)
        return ResultVo.success(InstanceOperationResponse(status = "draining", instanceId = instanceId))
    }

    /**
     * Get all registered instances.
     */
    @GetMapping("/instance/list")
    fun listInstances(): ResultVo<List<InstanceInfo>> {
        val instances = instanceRegistry.getAllActiveInstances()
        val result = instances.map { inst ->
            InstanceInfo(
                instanceId = inst.instanceId,
                host = inst.host,
                port = inst.port,
                status = inst.status,
                lastHeartbeat = inst.lastHeartbeat.toString(),
            )
        }
        return ResultVo.success(result)
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    fun health(): ResultVo<RouterHealthResponse> {
        val healthyCount = instanceRegistry.getHealthyInstances().size
        return ResultVo.success(RouterHealthResponse(status = "UP", healthyInstances = healthyCount))
    }

    /**
     * Get cache statistics for monitoring.
     */
    @GetMapping("/metrics/cache")
    fun cacheMetrics(): ResultVo<Map<String, Any>> {
        val combined = mutableMapOf<String, Any>(
            "instanceRegistry" to instanceRegistry.javaClass.simpleName,
            "sessionMappingService" to sessionMappingService.javaClass.simpleName,
        )
        return ResultVo.success(combined)
    }
}
