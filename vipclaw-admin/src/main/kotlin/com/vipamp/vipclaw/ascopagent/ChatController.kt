package com.vipamp.vipclaw.ascopagent

import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.agent.adaptor.PlanNote
import com.vipamp.vipclaw.agent.chat.ChatEvent
import com.vipamp.vipclaw.agent.chat.MessageLog
import com.vipamp.vipclaw.ascopagent.dto.ChatRequest
import com.vipamp.vipclaw.ascopagent.dto.ConfirmRequest
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

/**
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Description: ChatController
 * @Project: vipclaw
 */
@RestController
@RequestMapping
class ChatController(
    private val chatService: ChatService
) {

    @GetMapping("/health")
    @Schema(description = "健康检查")
    fun health(): ResultVo<String> {
        return ResultVo.success("OK")
    }

    @PostMapping("/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Schema(description = "聊天")
    fun chat(@RequestBody request: ChatRequest): Flux<ChatEvent> {
        return chatService.chat(request)
    }

    @PostMapping("/confirm", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Schema(description = "工具执行确认")
    fun confirm(@RequestBody request: ConfirmRequest): Flux<ChatEvent> {
        return chatService.confirm(request)
    }

    @DeleteMapping("/session/{sessionId}")
    @Schema(description = "清空当前会话")
    fun clearSession(@PathVariable("sessionId") sessionId: String): ResultVo<String> {
        try {
            chatService.clearSession(sessionId)
            return ResultVo.success("OK");
        } catch (e: Exception) {
            return ResultVo.error(e.toString());
        }
    }

    @GetMapping("/session/{sessionId}")
    @Schema(description = "获取历史会话")
    fun getSession(@PathVariable("sessionId") sessionId: String): ResultVo<List<MessageLog>> {
        try {
            return ResultVo.success(chatService.loadSessionMessages(sessionId))
        } catch (e: Exception) {
            return ResultVo.error(e.toString());
        }
    }

    @GetMapping("/session/{sessionId}/plans")
    @Schema(description = "获取会话的历史计划列表")
    fun getSessionPlans(@PathVariable("sessionId") sessionId: String): ResultVo<List<PlanNote>> {
        try {
            return ResultVo.success(chatService.loadSessionHistoryPlan(sessionId))
        } catch (e: Exception) {
            return ResultVo.error(e.toString());
        }
    }

    @GetMapping("/session/{sessionId}/current-plan")
    @Schema(description = "获取会话的当前计划")
    fun getCurrentPlan(@PathVariable("sessionId") sessionId: String): ResultVo<PlanNote?> {
        return try {
            ResultVo.success(chatService.loadSessionCurrentPlanNote(sessionId))
        } catch (e: Exception) {
            ResultVo.error(e.toString());
        }
    }
}
