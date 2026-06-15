package com.agnetix.harnax.router.controller

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.dto.InstanceInfo
import com.agnetix.harnax.router.dto.InstanceOperationResponse
import com.agnetix.harnax.router.dto.RouterHealthResponse
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

/**
 * Session Router Controller.
 * Provides endpoints for:
 * - Proxying chat requests to agent-service instances
 * - Proxying SSE streaming requests
 * - Agent-service instance registration and heartbeat
 */
@RestController
@RequestMapping("/api/router")
class SessionRouterController(
    private val instanceRegistry: InstanceRegistry,
    private val sessionMappingService: SessionMappingService,
    private val sessionRouterService: com.agnetix.harnax.router.proxy.SessionRouterService,
) {

    private val log = LoggerFactory.getLogger(SessionRouterController::class.java)

    /**
     * Proxy a direct (non-streaming) chat request to the correct agent-service instance.
     */
    @PostMapping("/agent/chat")
    suspend fun proxyChat(@RequestBody request: ChatAgentRequest): ResultVo<ChatResponse> {
        log.debug("Received chat proxy request for session: ${request.sessionId}")
        return sessionRouterService.proxyChatRequest(request)
    }

    /**
     * Proxy an SSE streaming chat request.
     * Accepts AgentRequest in body, returns Flux<ChatEvent> as text/event-stream.
     */
    @PostMapping("/agent/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun proxyChatStream(@RequestBody request: ChatAgentRequest): Flux<ChatEvent> {
        log.debug("Received stream proxy request for session: ${request.sessionId}")
        return sessionRouterService.proxyStreamRequest(request)
    }

    /**
     * Proxy a command request to the correct agent-service instance.
     */
    @PostMapping("/agent/command")
    suspend fun proxyCommand(@RequestBody request: CommandAgentRequest): ResultVo<CommandResponse> {
        log.debug("Received command proxy request for session: ${request.sessionId}")
        return sessionRouterService.proxyCommandRequest(request)
    }

    /**
     * Proxy a confirm request (SSE streaming) to the correct agent-service instance.
     */
    @PostMapping("/agent/confirm", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun proxyConfirm(@RequestBody request: ConfirmAgentRequest): Flux<ChatEvent> {
        log.debug("Received confirm proxy request for session: ${request.sessionId}")
        return sessionRouterService.proxyConfirmStreamRequest(request)
    }

    /**
     * Proxy a clear session request to the correct agent-service instance.
     */
    @DeleteMapping("/agent/session/{sessionId}")
    suspend fun proxyClearSession(@PathVariable sessionId: String): ResultVo<String> {
        log.debug("Received clear session proxy request for session: $sessionId")
        return sessionRouterService.proxyClearSession(sessionId)
    }

    /**
     * Proxy a load history request to the correct agent-service instance.
     */
    @GetMapping("/agent/chat/history/{sessionId}")
    suspend fun proxyLoadHistory(@PathVariable sessionId: String): ResultVo<List<Any>> {
        log.debug("Received load history proxy request for session: $sessionId")
        return sessionRouterService.proxyLoadHistory(sessionId)
    }

    /**
     * Proxy a load plans request to the correct agent-service instance.
     */
    @GetMapping("/agent/session/{sessionId}/plans")
    suspend fun proxyLoadPlans(@PathVariable sessionId: String): ResultVo<List<Any>> {
        log.debug("Received load plans proxy request for session: $sessionId")
        return sessionRouterService.proxyLoadPlans(sessionId)
    }

    /**
     * Proxy a load current plan request to the correct agent-service instance.
     */
    @GetMapping("/agent/session/{sessionId}/current-plan")
    suspend fun proxyLoadCurrentPlan(@PathVariable sessionId: String): ResultVo<Any?> {
        log.debug("Received load current plan proxy request for session: $sessionId")
        return sessionRouterService.proxyLoadCurrentPlan(sessionId)
    }

    /**
     * Register a new agent-service instance.
     */
    @PostMapping("/instance/register")
    fun registerInstance(
        @RequestParam instanceId: String,
        @RequestParam host: String,
        @RequestParam port: Int,
    ): ResultVo<InstanceOperationResponse> {
        log.info("Instance registration request: $instanceId at $host:$port")
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
        sessionMappingService.rebindAllSessions(instanceId, "")
        return ResultVo.success(InstanceOperationResponse(status = "unregistered", instanceId = instanceId))
    }

    /**
     * Get all registered instances.
     */
    @GetMapping("/instance/list")
    fun listInstances(): ResultVo<List<InstanceInfo>> {
        val instances = instanceRegistry.getHealthyInstances()
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
}
