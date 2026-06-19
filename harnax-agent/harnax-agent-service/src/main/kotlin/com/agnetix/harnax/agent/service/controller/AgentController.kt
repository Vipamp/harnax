package com.agnetix.harnax.agent.service.controller

import com.agnetix.harnax.agent.adaptor.PlanNote
import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.service.runner.AgentRunner
import com.agnetix.harnax.auth.InternalOnly
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.common.error.HarnaxErrorCode
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
@InternalOnly
@Tag(name = "Agent Processing", description = "Agent message processing APIs")
class AgentController(
    private val agentRunner: AgentRunner,
) {

    private val log = LoggerFactory.getLogger(AgentController::class.java)

    /**
     * Process a chat message with direct (non-streaming) output.
     * Collects all streaming events internally and returns an aggregated ChatResponse.
     */
    @PostMapping("/chat")
    @Operation(summary = "Process chat message (direct output)", description = "Send an AgentRequest and receive an aggregated ChatResponse")
    fun chat(@RequestBody request: ChatAgentRequest): ResultVo<ChatResponse> {
        log.info("Processing direct request for session=${request.sessionId}, type=${request.type}")
        return try {
            ResultVo.success(agentRunner.process(request))
        } catch (e: Exception) {
            ResultVo.error(e.message ?: "Failed to process chat")
        }
    }

    /**
     * Process a chat message with streaming output.
     * Accepts AgentRequest in the body (type, sessionId, message).
     * Returns Flux<ChatEvent> streamed as text/event-stream.
     */
    @PostMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(summary = "Process chat message with streaming", description = "Send an AgentRequest and receive streaming ChatEvent response via SSE")
    fun chatStream(@RequestBody request: ChatAgentRequest): Flux<ChatEvent> {
        log.info("Processing stream request for session=${request.sessionId}, type=${request.type}")
        return agentRunner.streamProcess(request)
            .onErrorResume { e ->
                log.error("Unhandled stream error for session=${request.sessionId}", e)
                Flux.just(
                    ErrorChatEvent(code = HarnaxErrorCode.SYSTEM_ERROR.code, message = e.message ?: "Internal error"),
                    EndEventChatEvent(),
                )
            }
    }

    /**
     * Execute a command request.
     * Accepts AgentRequest with type=COMMAND and a Command payload.
     */
    @PostMapping("/command")
    @Operation(summary = "Execute command", description = "Execute a command via AgentRequest and receive CommandResponse")
    fun executeCommand(@RequestBody request: CommandAgentRequest): ResultVo<CommandResponse> {
        log.info("Processing command request for session=${request.sessionId}, command=${request.command}")
        return try {
            ResultVo.success(agentRunner.executeCommand(request))
        } catch (e: Exception) {
            ResultVo.error(e.message ?: "Failed to execute command")
        }
    }

    /**
     * Interrupt the ongoing stream for a session.
     */
    @PostMapping("/chat/interrupt/{sessionId}")
    @Operation(summary = "Interrupt active stream", description = "Cancel the ongoing streaming response for a session")
    fun interrupt(@PathVariable sessionId: String): ResultVo<String> {
        log.info("Interrupting stream for session=$sessionId")
        agentRunner.interrupt(sessionId)
        return ResultVo.success("OK")
    }

    /**
     * Load historical messages for a session.
     */
    @GetMapping("/chat/history/{sessionId}")
    @Operation(summary = "Load chat history", description = "Get historical messages for a session")
    fun loadHistory(@PathVariable sessionId: String): ResultVo<List<MessageLog>> {
        log.info("Loading history for session=$sessionId")
        return try {
            ResultVo.success(agentRunner.loadHistory(sessionId))
        } catch (e: Exception) {
            ResultVo.error(e.message ?: "Failed to load history")
        }
    }

    /**
     * Confirm or reject pending tool execution.
     * Returns streaming response from the resumed or cancelled agent.
     */
    @PostMapping("/confirm", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(summary = "Confirm tool execution", description = "Confirm or reject pending tool calls and resume agent streaming")
    fun confirm(@RequestBody request: ConfirmAgentRequest): Flux<ChatEvent> {
        log.info("Confirm request for session=${request.sessionId}, confirmed=${request.isConfirmed}")
        return try {
            agentRunner.confirm(request)
        } catch (e: Exception) {
            log.error("Error confirming for session=${request.sessionId}: ${e.message}", e)
            Flux.just(
                ErrorChatEvent(code = HarnaxErrorCode.SYSTEM_ERROR.code, message = e.message ?: "Confirm failed"),
                EndEventChatEvent(),
            )
        }
    }

    /**
     * Clear session state and cached agent.
     */
    @DeleteMapping("/session/{sessionId}")
    @Operation(summary = "Clear session", description = "Clear session state, cached agent, and sandbox")
    fun clearSession(@PathVariable sessionId: String): ResultVo<String> {
        log.info("Clearing session=$sessionId")
        return try {
            agentRunner.clearSession(sessionId)
            ResultVo.success("OK")
        } catch (e: Exception) {
            ResultVo.error(e.message ?: "Failed to clear session")
        }
    }

    /**
     * Load plan history for a session.
     */
    @GetMapping("/session/{sessionId}/plans")
    @Operation(summary = "Load plan history", description = "Get all plans for a session")
    fun loadPlans(@PathVariable sessionId: String): ResultVo<List<PlanNote>> {
        log.info("Loading plans for session=$sessionId")
        return try {
            ResultVo.success(agentRunner.loadPlans(sessionId))
        } catch (e: Exception) {
            ResultVo.error(e.message ?: "Failed to load plans")
        }
    }

    /**
     * Load the current active plan for a session.
     */
    @GetMapping("/session/{sessionId}/current-plan")
    @Operation(summary = "Load current plan", description = "Get the current active plan for a session")
    fun loadCurrentPlan(@PathVariable sessionId: String): ResultVo<PlanNote?> {
        log.info("Loading current plan for session=$sessionId")
        return try {
            ResultVo.success(agentRunner.loadCurrentPlan(sessionId))
        } catch (e: Exception) {
            ResultVo.error(e.message ?: "Failed to load current plan")
        }
    }

    /**
     * Health check endpoint.
     * Used by session-router for heartbeat verification.
     */
    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Check if agent-service is healthy")
    fun health(): ResultVo<String> = ResultVo.success("UP")
}
