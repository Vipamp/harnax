package com.vipamp.vipclaw.ascopagent

import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.agent.adaptor.PlanNote
import com.vipamp.vipclaw.agent.chat.ChatEvent
import com.vipamp.vipclaw.agent.chat.MessageLog
import com.vipamp.vipclaw.admin.util.JwtUtil
import com.vipamp.vipclaw.ascopagent.dto.ChatRequest
import com.vipamp.vipclaw.ascopagent.dto.ConfirmRequest
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux

/**
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Description: ChatController
 * @Project: vipclaw
 */
@RestController
@RequestMapping("/ai")
class ChatController(
    private val chatService: ChatService,
    private val jwtUtil: JwtUtil
) {

    /**
     * 手动验证 JWT Token（WebFlux 端点需要）
     */
    private fun validateJwtToken(request: HttpServletRequest) {
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "未授权或 Token 无效")
        }
        
        val token = authHeader.substring(7)
        if (!jwtUtil.validateToken(token)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token 无效或已过期")
        }
    }

    @PostMapping("/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Schema(description = "聊天")
    fun chat(@RequestBody request: ChatRequest, httpServletRequest: HttpServletRequest): Flux<ChatEvent> {
        validateJwtToken(httpServletRequest)
        return chatService.chat(request)
    }

    @PostMapping("/confirm", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Schema(description = "工具执行确认")
    fun confirm(@RequestBody request: ConfirmRequest, httpServletRequest: HttpServletRequest): Flux<ChatEvent> {
        validateJwtToken(httpServletRequest)
        return chatService.confirm(request)
    }

    @DeleteMapping("/session/{sessionId}")
    @Schema(description = "清空当前会话")
    fun clearSession(@PathVariable("sessionId") sessionId: String, httpServletRequest: HttpServletRequest): ResultVo<String> {
        validateJwtToken(httpServletRequest)
        try {
            chatService.clearSession(sessionId)
            return ResultVo.success("OK");
        } catch (e: Exception) {
            return ResultVo.error(e.toString());
        }
    }

    @GetMapping("/session/{sessionId}")
    @Schema(description = "获取历史会话")
    fun getSession(@PathVariable("sessionId") sessionId: String, httpServletRequest: HttpServletRequest): ResultVo<List<MessageLog>> {
        validateJwtToken(httpServletRequest)
        try {
            return ResultVo.success(chatService.loadSessionMessages(sessionId))
        } catch (e: Exception) {
            return ResultVo.error(e.toString());
        }
    }

    @GetMapping("/session/{sessionId}/plans")
    @Schema(description = "获取会话的历史计划列表")
    fun getSessionPlans(@PathVariable("sessionId") sessionId: String, httpServletRequest: HttpServletRequest): ResultVo<List<PlanNote>> {
        validateJwtToken(httpServletRequest)
        try {
            return ResultVo.success(chatService.loadSessionHistoryPlan(sessionId))
        } catch (e: Exception) {
            return ResultVo.error(e.toString());
        }
    }

    @GetMapping("/session/{sessionId}/current-plan")
    @Schema(description = "获取会话的当前计划")
    fun getCurrentPlan(@PathVariable("sessionId") sessionId: String, httpServletRequest: HttpServletRequest): ResultVo<PlanNote?> {
        validateJwtToken(httpServletRequest)
        return try {
            ResultVo.success(chatService.loadSessionCurrentPlanNote(sessionId))
        } catch (e: Exception) {
            ResultVo.error(e.toString());
        }
    }
}
