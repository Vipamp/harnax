package com.agnetix.harnax.agent.service.controller

import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.service.dto.ChatRequest
import com.agnetix.harnax.agent.service.dto.ChatResponse
import com.agnetix.harnax.agent.service.runner.AgentRunner
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

/**
 * Agent Controller.
 * Provides endpoints for processing chat messages through the agent.
 * These endpoints are called by the session-router after routing.
 */
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent Processing", description = "Agent message processing APIs")
class AgentController(
    private val agentRunner: AgentRunner,
) {

    private val log = LoggerFactory.getLogger(AgentController::class.java)

    /**
     * Process a chat message synchronously.
     * Called by session-router after routing to this instance.
     */
    @PostMapping("/chat")
    @Operation(summary = "Process chat message", description = "Send a message to the agent and receive a response")
    suspend fun chat(
        @RequestParam sessionId: String,
        @RequestParam agentId: Long,
        @RequestBody request: ChatRequest,
    ): Map<String, Any> {
        log.info("Processing chat request for session=$sessionId, agentId=$agentId")
        val response = agentRunner.process(sessionId, request.message, agentId)
        return mapOf(
            "code" to 200,
            "message" to "success",
            "data" to ChatResponse(response),
            "sessionId" to sessionId,
        )
    }

    /**
     * Process a chat message with streaming output.
     * Called by session-router after routing to this instance.
     * Returns Flux<ChatEvent> which Spring serializes as text/event-stream.
     * Jackson polymorphism handles automatic JSON serialization of ChatEvent subtypes.
     */
    @PostMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(summary = "Process chat message with streaming", description = "Send a message and receive streaming response via SSE")
    fun chatStream(
        @RequestParam sessionId: String,
        @RequestParam agentId: Long,
        @RequestBody request: ChatRequest,
    ): Flux<ChatEvent> {
        log.info("Processing stream request for session=$sessionId, agentId=$agentId")
        return agentRunner.streamProcess(sessionId, request.message, agentId)
    }

    /**
     * Health check endpoint.
     * Used by session-router for heartbeat verification.
     */
    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Check if agent-service is healthy")
    fun health(): Map<String, String> = mapOf("status" to "UP")
}
