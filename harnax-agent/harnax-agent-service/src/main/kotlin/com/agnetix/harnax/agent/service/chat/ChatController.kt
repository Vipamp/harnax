package com.agnetix.harnax.agent.service.chat

import com.agnetix.harnax.agent.adaptor.PlanNote
import com.agnetix.harnax.agent.chat.ChatEvent
import com.agnetix.harnax.agent.chat.MessageLog
import com.agnetix.harnax.agent.service.chat.dto.ChatRequest
import com.agnetix.harnax.agent.service.chat.dto.ConfirmRequest
import com.agnetix.harnax.agent.service.dto.ResultVo
import com.agnetix.harnax.agent.service.util.JwtUtil
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

    @PostMapping("/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Schema(description = "Chat")
    fun chat(@RequestBody request: ChatRequest, httpServletRequest: HttpServletRequest): Flux<ChatEvent> {
        validateJwtToken(httpServletRequest)
        return chatService.chat(request)
    }

    @PostMapping("/confirm", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Schema(description = "Tool execution confirmation")
    fun confirm(@RequestBody request: ConfirmRequest, httpServletRequest: HttpServletRequest): Flux<ChatEvent> {
        validateJwtToken(httpServletRequest)
        return chatService.confirm(request)
    }

    @DeleteMapping("/session/{sessionId}")
    @Schema(description = "Clear current session")
    fun clearSession(
        @PathVariable("sessionId") sessionId: String,
        httpServletRequest: HttpServletRequest,
    ): ResultVo<String> {
        validateJwtToken(httpServletRequest)
        try {
            chatService.clearSession(sessionId)
            return ResultVo.success("OK")
        } catch (e: Exception) {
            return ResultVo.error(e.toString())
        }
    }

    @GetMapping("/session/{sessionId}")
    @Schema(description = "Get historical session")
    fun getSession(
        @PathVariable("sessionId") sessionId: String,
        httpServletRequest: HttpServletRequest,
    ): ResultVo<List<MessageLog>> {
        validateJwtToken(httpServletRequest)
        try {
            return ResultVo.success(chatService.loadSessionMessages(sessionId))
        } catch (e: Exception) {
            return ResultVo.error(e.toString())
        }
    }

    @GetMapping("/session/{sessionId}/plans")
    @Schema(description = "Get session history plan list")
    fun getSessionPlans(
        @PathVariable("sessionId") sessionId: String,
        httpServletRequest: HttpServletRequest,
    ): ResultVo<List<PlanNote>> {
        validateJwtToken(httpServletRequest)
        try {
            return ResultVo.success(chatService.loadSessionHistoryPlan(sessionId))
        } catch (e: Exception) {
            return ResultVo.error(e.toString())
        }
    }

    @GetMapping("/session/{sessionId}/current-plan")
    @Schema(description = "Get session current plan")
    fun getCurrentPlan(
        @PathVariable("sessionId") sessionId: String,
        httpServletRequest: HttpServletRequest,
    ): ResultVo<PlanNote?> {
        validateJwtToken(httpServletRequest)
        return try {
            ResultVo.success(chatService.loadSessionCurrentPlanNote(sessionId))
        } catch (e: Exception) {
            ResultVo.error(e.toString())
        }
    }
}
