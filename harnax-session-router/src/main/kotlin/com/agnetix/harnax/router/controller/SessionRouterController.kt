package com.agnetix.harnax.router.controller

import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
     * Proxy a synchronous chat request to the correct agent-service instance.
     */
    @PostMapping("/agent/chat")
    suspend fun proxyChat(
        @RequestParam sessionId: String,
        @RequestParam(required = false) agentId: Long?,
        @RequestBody requestBody: Map<String, Any>,
    ): ResponseEntity<String> {
        log.debug("Received chat proxy request for session: $sessionId")
        return sessionRouterService.proxyChatRequest(sessionId, agentId, requestBody)
    }

    /**
     * Proxy an SSE streaming chat request.
     * Returns Flux<ChatEvent> which Spring serializes as text/event-stream.
     * Jackson polymorphism handles automatic JSON serialization.
     */
    @PostMapping("/agent/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun proxyChatStream(
        @RequestParam sessionId: String,
        @RequestParam(required = false) agentId: Long?,
        @RequestBody requestBody: Map<String, Any>,
    ): Flux<ChatEvent> {
        log.debug("Received stream proxy request for session: $sessionId")
        return Flux.from(sessionRouterService.proxyStreamRequest(sessionId, agentId, requestBody))
    }

    /**
     * Register a new agent-service instance.
     */
    @PostMapping("/instance/register")
    fun registerInstance(
        @RequestParam instanceId: String,
        @RequestParam host: String,
        @RequestParam port: Int,
    ): ResponseEntity<Map<String, String>> {
        log.info("Instance registration request: $instanceId at $host:$port")
        instanceRegistry.registerInstance(instanceId, host, port)
        return ResponseEntity.ok(mapOf("status" to "registered", "instanceId" to instanceId))
    }

    /**
     * Refresh heartbeat for an existing instance.
     */
    @PostMapping("/instance/heartbeat")
    fun heartbeat(@RequestParam instanceId: String): ResponseEntity<Map<String, String>> {
        instanceRegistry.refreshHeartbeat(instanceId)
        return ResponseEntity.ok(mapOf("status" to "ok", "instanceId" to instanceId))
    }

    /**
     * Unregister an agent-service instance.
     */
    @PostMapping("/instance/unregister")
    fun unregisterInstance(@RequestParam instanceId: String): ResponseEntity<Map<String, String>> {
        log.info("Instance unregistration request: $instanceId")
        instanceRegistry.unregisterInstance(instanceId)
        sessionMappingService.rebindAllSessions(instanceId, "")
        return ResponseEntity.ok(mapOf("status" to "unregistered", "instanceId" to instanceId))
    }

    /**
     * Get all registered instances.
     */
    @GetMapping("/instance/list")
    fun listInstances(): ResponseEntity<List<Map<String, Any>>> {
        val instances = instanceRegistry.getHealthyInstances()
        val result = instances.map { inst ->
            mapOf(
                "instanceId" to inst.instanceId,
                "host" to inst.host,
                "port" to inst.port,
                "status" to inst.status,
                "lastHeartbeat" to inst.lastHeartbeat.toString(),
            )
        }
        return ResponseEntity.ok(result)
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    fun health(): Map<String, Any> {
        val healthyCount = instanceRegistry.getHealthyInstances().size
        return mapOf(
            "status" to "UP",
            "healthyInstances" to healthyCount,
        )
    }
}
