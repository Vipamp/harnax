package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.*
import com.agnetix.harnax.admin.service.SessionService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

/**
 * Session management controller
 */
@RestController
@RequestMapping("/api/sessions")
@Tag(name = "Session Management", description = "Session related APIs")
class SessionController(
    private val sessionService: SessionService,
) {

    private val log = LoggerFactory.getLogger(SessionController::class.java)

    @GetMapping("/page")
    @Operation(summary = "Get session list with pagination", description = "Paginated query for session information")
    fun pageSession(
        @Parameter(description = "Page number", example = "1") @RequestParam(
            name = "pageNum",
            defaultValue = "1",
        ) pageNum: Int?,
        @Parameter(description = "Page size", example = "10") @RequestParam(
            name = "pageSize",
            defaultValue = "10",
        ) pageSize: Int?,
        @Parameter(description = "Session name") @RequestParam(name = "keyword", required = false) keyword: String?,
        @Parameter(description = "Status filter") @RequestParam(name = "status", required = false) status: Int?,
    ): ResultVo<Page<SessionResponse>> = try {
        val page = sessionService.page(
            keyword,
            status,
            pageNum ?: 1,
            pageSize ?: 10,
        )
        ResultVo.success(page.mapRecords { sessionService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get session list", e)
        ResultVo.error(e.message ?: "Failed to get session list")
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get session details", description = "Get session information by session ID")
    fun getSession(
        @Parameter(description = "Session ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<SessionResponse?> = try {
        val session = sessionService.getSession(id)
        ResultVo.success(session?.let { sessionService.convertToResponse(it) })
    } catch (e: Exception) {
        log.error("Failed to get session details", e)
        ResultVo.error(e.message ?: "Failed to get session details")
    }

    @GetMapping("/check-title")
    @Operation(summary = "Check if session name exists", description = "Check if session name already exists")
    fun checkSessionTitle(
        @Parameter(description = "Session name") @RequestParam(name = "title") title: String,
    ): ResultVo<Boolean> = try {
        val exists = sessionService.existsByTitle(title)
        ResultVo.success(exists)
    } catch (e: Exception) {
        log.error("Failed to check session name", e)
        ResultVo.error(e.message ?: "Failed to check session name")
    }

    @PostMapping
    @Operation(summary = "Create session", description = "Add new session information")
    fun createSession(
        @Validated @RequestBody request: SessionCreateRequest,
    ): ResultVo<Void> = try {
        if (sessionService.createSession(request)) ResultVo.success() else ResultVo.error("Failed to create session")
    } catch (e: Exception) {
        log.error("Failed to create session", e)
        ResultVo.error(e.message ?: "Failed to create session")
    }

    @PutMapping("/update/{id}")
    @Operation(summary = "Update session", description = "Update session information by session ID")
    fun updateSession(
        @Parameter(description = "Session ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Session entity") @RequestBody request: SessionCreateRequest,
    ): ResultVo<Void> = try {
        if (sessionService.updateSession(
                id,
                request,
            )
        ) {
            ResultVo.success()
        } else {
            ResultVo.error("Failed to update session")
        }
    } catch (e: Exception) {
        log.error("Failed to update session", e)
        ResultVo.error(e.message ?: "Failed to update session")
    }

    @GetMapping("/{sessionId}/config")
    @Schema(description = "Get session chat configuration")
    fun getSessionConfig(
        @PathVariable("sessionId") sessionId: String,
    ): ResultVo<SessionResponse?> = try {
        val sessionChatConfig = sessionService.getSessionChatConfig(sessionId)
        ResultVo.success(sessionChatConfig?.let { sessionService.convertToResponse(it) })
    } catch (e: Exception) {
        ResultVo.error(e.toString())
    }

    @PutMapping("/{sessionId}/config")
    @Schema(description = "Update session chat configuration")
    fun updateSessionConfig(
        @PathVariable("sessionId") sessionId: String,
        @RequestBody request: SessionChatUpdateRequest,
    ): ResultVo<Void> = try {
        sessionService.updateSessionChatConfig(sessionId, request)
        ResultVo.success()
    } catch (e: Exception) {
        ResultVo.error(e.toString())
    }

    @PutMapping("/toggle/{id}")
    @Operation(summary = "Toggle session status", description = "Toggle session status by session ID")
    fun toggleSession(
        @Parameter(description = "Session ID") @PathVariable(name = "id") id: Long,
        @Parameter(description = "Session status") @RequestParam(name = "status") status: Int,
    ): ResultVo<Void> = try {
        if (sessionService.toggleSessionStatus(
                id,
                status,
            )
        ) {
            ResultVo.success()
        } else {
            ResultVo.error("Failed to update session")
        }
    } catch (e: Exception) {
        log.error("Failed to toggle session status", e)
        ResultVo.error(e.message ?: "Failed to toggle session status")
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete session", description = "Delete session by session ID")
    fun deleteSession(
        @Parameter(description = "Session ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        if (sessionService.deleteSession(id)) ResultVo.success() else ResultVo.error("Failed to delete session")
    } catch (e: Exception) {
        log.error("Failed to delete session", e)
        ResultVo.error(e.message ?: "Failed to delete session")
    }
}
