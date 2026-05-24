package com.agnetix.harnax.router.controller

import com.agnetix.harnax.router.service.InstanceRegistry
import com.agnetix.harnax.router.service.SessionMappingService
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import reactor.core.publisher.Flux
import java.util.concurrent.Executors

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
     * Uses SseEmitter to stream events back to the caller.
     */
    @PostMapping("/agent/chat/stream")
    fun proxyChatStream(
        @RequestParam sessionId: String,
        @RequestParam(required = false) agentId: Long?,
        @RequestBody requestBody: Map<String, Any>,
    ): SseEmitter {
        log.debug("Received stream proxy request for session: $sessionId")
        val emitter = SseEmitter(0L) // No timeout

        Executors.newSingleThreadExecutor().execute {
            try {
                val flux = sessionRouterService.proxyStreamRequest(sessionId, agentId, requestBody)
                (flux as Flux<String>).doOnNext { event ->
                    emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON))
                }.doOnComplete {
                    emitter.complete()
                }.doOnError { e ->
                    emitter.completeWithError(e)
                }.subscribe()
            } catch (e: Exception) {
                log.error("SSE proxy error: ${e.message}", e)
                emitter.completeWithError(e)
            }
        }

        return emitter
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
