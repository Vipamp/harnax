package com.agnetix.harnax.agent.service.controller

import com.agnetix.harnax.agent.service.dto.ChatRequest
import com.agnetix.harnax.agent.service.dto.ChatResponse
import com.agnetix.harnax.agent.service.runner.AgentRunner
import com.agnetix.harnax.agent.service.runner.AgentStreamEvent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

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
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
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
     * Process a chat message with SSE streaming output.
     * Called by session-router after routing to this instance.
     */
    @PostMapping("/chat/stream")
    @Operation(summary = "Process chat message with streaming", description = "Send a message and receive streaming response via SSE")
    fun chatStream(
        @RequestParam sessionId: String,
        @RequestParam agentId: Long,
        @RequestBody request: ChatRequest,
    ): SseEmitter {
        log.info("Processing stream request for session=$sessionId, agentId=$agentId")
        val emitter = SseEmitter(0L) // No timeout

        coroutineScope.launch {
            try {
                agentRunner.streamProcess(sessionId, request.message, agentId)
                    .collect { event ->
                        when (event) {
                            is AgentStreamEvent.TextStreamEvent -> {
                                emitter.send(
                                    SseEmitter.event()
                                        .name("message")
                                        .data(
                                            mapOf("content" to event.content, "isLast" to event.isLast),
                                            MediaType.APPLICATION_JSON,
                                        ),
                                )
                            }
                            is AgentStreamEvent.ThinkingStreamEvent -> {
                                emitter.send(
                                    SseEmitter.event()
                                        .name("thinking")
                                        .data(
                                            mapOf("content" to event.content),
                                            MediaType.APPLICATION_JSON,
                                        ),
                                )
                            }
                            is AgentStreamEvent.EndStreamEvent -> {
                                emitter.send(
                                    SseEmitter.event()
                                        .name("end")
                                        .data(
                                            mapOf("fullContent" to event.fullContent),
                                            MediaType.APPLICATION_JSON,
                                        ),
                                )
                                emitter.complete()
                            }
                            is AgentStreamEvent.ErrorStreamEvent -> {
                                emitter.send(
                                    SseEmitter.event()
                                        .name("error")
                                        .data(
                                            mapOf("error" to event.error),
                                            MediaType.APPLICATION_JSON,
                                        ),
                                )
                                emitter.completeWithError(event.cause ?: RuntimeException(event.error))
                            }
                        }
                    }
            } catch (e: Exception) {
                log.error("Stream processing error: ${e.message}", e)
                emitter.completeWithError(e)
            }
        }

        return emitter
    }

    /**
     * Health check endpoint.
     * Used by session-router for heartbeat verification.
     */
    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Check if agent-service is healthy")
    fun health(): Map<String, String> = mapOf("status" to "UP")
}
