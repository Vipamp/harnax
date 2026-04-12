package com.vipamp.vipclaw.agent.service

import com.vipamp.vipclaw.agent.chat.ChatEvent
import com.vipamp.vipclaw.agent.service.dto.ChatRequest
import com.vipamp.vipclaw.agent.service.dto.ConfirmRequest
import com.vipamp.vipclaw.common.Result
import io.swagger.v3.oas.annotations.media.Schema
import lombok.RequiredArgsConstructor
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

/**
 * @Author: heqingsong
 * @Date: 2026/4/12
 * @Description: ChatController
 * @Project: vipclaw
 */
@RestController
@RequestMapping
@RequiredArgsConstructor
class ChatController(
    private val chatService: ChatService
) {

    @GetMapping("/health")
    @Schema(description = "健康检查")
    fun health(): Result<String> {
        return Result.success("OK")
    }

    @GetMapping("/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Schema(description = "聊天")
    fun chat(request: ChatRequest): Flux<ChatEvent> {
        return chatService.chat(request)
    }

    @GetMapping("/confirm", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Schema(description = "工具执行确认")
    fun confirm(request: ConfirmRequest): Flux<ChatEvent> {
        return chatService.confirm(request)
    }

    @DeleteMapping("/session/{sessionId}")
    @Schema(description = "工具执行确认")
    fun clearSession(sessionId: String): Result<String> {
        try {
            chatService.clearSession(sessionId)
            return Result.success("OK");
        } catch (e: Exception) {
            return Result.error(e.toString());
        }
    }

//    @DeleteMapping("/session/{sessionId}")
//    @Schema(description = "工具执行确认")
//    fun loadSessionMessages(sessionId: String): Result<List<MessageLog>> {
//        return Result.success(chatService.loadSessionMessages(sessionId))
//    }
}
