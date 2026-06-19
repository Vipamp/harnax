package com.agnetix.harnax.admin.controller.mp

import com.agnetix.harnax.admin.dto.mp.MpChatMessageDto
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.mp.MpMessageService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * Mobile chat message history controller.
 * AI streaming chat goes directly to router SSE, not through this controller.
 */
@Tag(name = "MP Chat", description = "Mobile chat message history APIs")
@RestController
@RequestMapping("/api/mp/chat")
class MpChatController(
    private val mpMessageService: MpMessageService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/history/{sessionId}")
    @Operation(summary = "Get message history", description = "Get chat message history for a session")
    fun getHistory(
        @Parameter(description = "Session ID") @PathVariable("sessionId") sessionId: Long,
    ): ResultVo<List<MpChatMessageDto>> {
        val user = SecurityUtils.getCurrentUser()
            ?: return ResultVo.error("User not logged in")
        return ResultVo.success(mpMessageService.getHistory(user.id, sessionId))
    }

    @PostMapping("/history/{sessionId}")
    @Operation(summary = "Save messages", description = "Batch save chat messages for a session")
    fun saveMessages(
        @Parameter(description = "Session ID") @PathVariable("sessionId") sessionId: Long,
        @RequestBody messages: List<MpChatMessageDto>,
    ): ResultVo<Void> {
        val user = SecurityUtils.getCurrentUser()
            ?: return ResultVo.error("User not logged in")
        mpMessageService.saveMessages(user.id, sessionId, messages)
        return ResultVo.success()
    }

    @DeleteMapping("/history/{sessionId}")
    @Operation(summary = "Delete message history", description = "Delete all messages for a session")
    fun deleteHistory(
        @Parameter(description = "Session ID") @PathVariable("sessionId") sessionId: Long,
    ): ResultVo<Void> {
        val user = SecurityUtils.getCurrentUser()
            ?: return ResultVo.error("User not logged in")
        mpMessageService.deleteHistory(user.id, sessionId)
        return ResultVo.success()
    }
}
