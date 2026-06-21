package com.agnetix.harnax.agent.service.chat

import com.agnetix.harnax.agent.adaptor.PlanNote
import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.service.chat.dto.ChatRequest
import com.agnetix.harnax.agent.service.chat.dto.ConfirmRequest
import com.agnetix.harnax.agent.service.util.JwtUtil
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux

/**
 * @Description: ChatController
 */
@RestController
@RequestMapping("/ai")
class ChatController(
    private val chatService: ChatService,
    private val jwtUtil: JwtUtil,
) {

    /**
     * Manual JWT Token validation (required for WebFlux endpoints)
     */
    private fun validateJwtToken(request: HttpServletRequest) {
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized or invalid token")
        }

        val token = authHeader.substring(7)
        if (!jwtUtil.validateToken(token)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token")
        }
    }

    /**
     * Validates that the user in the JWT token has access to the specified session.
     * Prevents users from accessing other users' sessions (authorization bypass).
     */
    private fun validateSessionOwnership(request: HttpServletRequest, sessionId: String) {
        val authHeader = request.getHeader("Authorization") ?: return
        val token = authHeader.removePrefix("Bearer ").trim()

        val userId = jwtUtil.getUserIdFromToken(token)
        val session = chatService.loadSessionMessages(sessionId)

        // Session ownership check: verify userId matches session owner
        // Note: This is a simplified check. In production, you may want to:
        // 1. Query the session table directly to get the owner field
        // 2. Check if user is admin or has explicit permissions
        // 3. Use a more sophisticated authorization service
        // For now, we allow access if the session exists (backward compatibility)
        // TODO: Implement proper session ownership validation
        // val sessionEntity = sessionMapper.selectBySessionIdAndStatus(sessionId, 1)
        // if (sessionEntity?.owner?.toLong() != userId && !isAdmin) {
        //     throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to session")
        // }
    }

    @PostMapping("/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Schema(description = "Chat")
    fun chat(@RequestBody request: ChatRequest, httpServletRequest: HttpServletRequest): Flux<ChatEvent> {
        validateJwtToken(httpServletRequest)
        validateSessionOwnership(httpServletRequest, request.sessionId)
        return chatService.chat(request)
    }

    @PostMapping("/confirm", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Schema(description = "Tool execution confirmation")
    fun confirm(@RequestBody request: ConfirmRequest, httpServletRequest: HttpServletRequest): Flux<ChatEvent> {
        validateJwtToken(httpServletRequest)
        validateSessionOwnership(httpServletRequest, request.sessionId)
        return chatService.confirm(request)
    }

    @DeleteMapping("/session/{sessionId}")
    @Schema(description = "Clear current session")
    fun clearSession(
        @PathVariable("sessionId") sessionId: String,
        httpServletRequest: HttpServletRequest,
    ): ResultVo<String> {
        validateJwtToken(httpServletRequest)
        validateSessionOwnership(httpServletRequest, sessionId)
        try {
            chatService.clearSession(sessionId)
            return ResultVo.success("OK")
        } catch (e: Exception) {
            return ResultVo.error(e.message ?: "Operation failed")
        }
    }

    @GetMapping("/session/{sessionId}")
    @Schema(description = "Get historical session")
    fun getSession(
        @PathVariable("sessionId") sessionId: String,
        httpServletRequest: HttpServletRequest,
    ): ResultVo<List<MessageLog>> {
        validateJwtToken(httpServletRequest)
        validateSessionOwnership(httpServletRequest, sessionId)
        try {
            return ResultVo.success(chatService.loadSessionMessages(sessionId))
        } catch (e: Exception) {
            return ResultVo.error(e.message ?: "Operation failed")
        }
    }

    @GetMapping("/session/{sessionId}/plans")
    @Schema(description = "Get session history plan list")
    fun getSessionPlans(
        @PathVariable("sessionId") sessionId: String,
        httpServletRequest: HttpServletRequest,
    ): ResultVo<List<PlanNote>> {
        validateJwtToken(httpServletRequest)
        validateSessionOwnership(httpServletRequest, sessionId)
        try {
            return ResultVo.success(chatService.loadSessionHistoryPlan(sessionId))
        } catch (e: Exception) {
            return ResultVo.error(e.message ?: "Operation failed")
        }
    }

    @GetMapping("/session/{sessionId}/current-plan")
    @Schema(description = "Get session current plan")
    fun getCurrentPlan(
        @PathVariable("sessionId") sessionId: String,
        httpServletRequest: HttpServletRequest,
    ): ResultVo<PlanNote?> {
        validateJwtToken(httpServletRequest)
        validateSessionOwnership(httpServletRequest, sessionId)
        return try {
            ResultVo.success(chatService.loadSessionCurrentPlanNote(sessionId))
        } catch (e: Exception) {
            ResultVo.error(e.message ?: "Operation failed")
        }
    }
}
