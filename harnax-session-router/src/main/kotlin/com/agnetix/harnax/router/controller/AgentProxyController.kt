package com.agnetix.harnax.router.controller

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.proxy.SessionRouterService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

/**
 * Agent Proxy Controller.
 * Proxies chat, command, and session management requests to agent-service instances.
 * Both internal services and external API key callers can access these endpoints.
 */
@RestController
@RequestMapping("/api/router/agent")
class AgentProxyController(
    private val sessionRouterService: SessionRouterService,
) {

    private val log = LoggerFactory.getLogger(AgentProxyController::class.java)

    companion object {
        // Request attribute key for passing sessionId to the ApiCallLogFilter.
        // Controller sets this after @RequestBody parsing; filter reads it in the finally block.
        const val SESSION_ID_ATTR = "router.sessionId"
    }

    /**
     * Proxy a direct (non-streaming) chat request to the correct agent-service instance.
     */
    @PostMapping("/chat")
    suspend fun proxyChat(
        @RequestBody request: ChatAgentRequest,
        httpRequest: HttpServletRequest,
    ): ResultVo<ChatResponse> {
        httpRequest.setAttribute(SESSION_ID_ATTR, request.sessionId)
        log.info("[Router] Received chat proxy request for session: ${request.sessionId}, message='${request.message.take(50)}'")
        return sessionRouterService.proxyChatRequest(request)
    }

    /**
     * Proxy an SSE streaming chat request.
     * Accepts AgentRequest in body, returns Flux<ChatEvent> as text/event-stream.
     */
    @PostMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun proxyChatStream(
        @RequestBody request: ChatAgentRequest,
        httpRequest: HttpServletRequest,
    ): Flux<ChatEvent> {
        httpRequest.setAttribute(SESSION_ID_ATTR, request.sessionId)
        log.info("[Router] Received stream proxy request for session: ${request.sessionId}")
        return sessionRouterService.proxyStreamRequest(request)
            .doOnNext { event ->
                log.info("[Router→Channel] Forwarding event to channel for session=${request.sessionId}: ${event.javaClass.simpleName}")
            }
    }

    /**
     * Proxy a command request to the correct agent-service instance.
     */
    @PostMapping("/command")
    suspend fun proxyCommand(
        @RequestBody request: CommandAgentRequest,
        httpRequest: HttpServletRequest,
    ): ResultVo<CommandResponse> {
        httpRequest.setAttribute(SESSION_ID_ATTR, request.sessionId)
        log.debug("Received command proxy request for session: ${request.sessionId}")
        return sessionRouterService.proxyCommandRequest(request)
    }

    /**
     * Proxy a confirm request (SSE streaming) to the correct agent-service instance.
     */
    @PostMapping("/confirm", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun proxyConfirm(
        @RequestBody request: ConfirmAgentRequest,
        httpRequest: HttpServletRequest,
    ): Flux<ChatEvent> {
        httpRequest.setAttribute(SESSION_ID_ATTR, request.sessionId)
        log.debug("Received confirm proxy request for session: ${request.sessionId}")
        return sessionRouterService.proxyConfirmStreamRequest(request)
    }

    /**
     * Proxy a clear session request to the correct agent-service instance.
     */
    @DeleteMapping("/session/{sessionId}")
    suspend fun proxyClearSession(
        @PathVariable sessionId: String,
        httpRequest: HttpServletRequest,
    ): ResultVo<String> {
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        log.debug("Received clear session proxy request for session: $sessionId")
        return sessionRouterService.proxyClearSession(sessionId)
    }

    /**
     * Proxy a load history request to the correct agent-service instance.
     */
    @GetMapping("/chat/history/{sessionId}")
    suspend fun proxyLoadHistory(
        @PathVariable sessionId: String,
        httpRequest: HttpServletRequest,
    ): ResultVo<List<Any>> {
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        log.debug("Received load history proxy request for session: $sessionId")
        return sessionRouterService.proxyLoadHistory(sessionId)
    }

    /**
     * Proxy a load plans request to the correct agent-service instance.
     */
    @GetMapping("/session/{sessionId}/plans")
    suspend fun proxyLoadPlans(
        @PathVariable sessionId: String,
        httpRequest: HttpServletRequest,
    ): ResultVo<List<Any>> {
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        log.debug("Received load plans proxy request for session: $sessionId")
        return sessionRouterService.proxyLoadPlans(sessionId)
    }

    /**
     * Proxy a load current plan request to the correct agent-service instance.
     */
    @GetMapping("/session/{sessionId}/current-plan")
    suspend fun proxyLoadCurrentPlan(
        @PathVariable sessionId: String,
        httpRequest: HttpServletRequest,
    ): ResultVo<Any?> {
        httpRequest.setAttribute(SESSION_ID_ATTR, sessionId)
        log.debug("Received load current plan proxy request for session: $sessionId")
        return sessionRouterService.proxyLoadCurrentPlan(sessionId)
    }
}
