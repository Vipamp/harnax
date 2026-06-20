package com.agnetix.harnax.admin.controller.mp

import com.agnetix.harnax.admin.dto.mp.MpCreateSessionRequest
import com.agnetix.harnax.admin.dto.mp.MpSessionResponse
import com.agnetix.harnax.admin.dto.mp.MpUpdateSessionRequest
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.mp.MpSessionService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * Mobile session management controller.
 */
@Tag(name = "MP Sessions", description = "Mobile session management APIs")
@RestController
@RequestMapping("/api/mp/sessions")
class MpSessionController(
    private val mpSessionService: MpSessionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    @Operation(summary = "List sessions", description = "Get all sessions for current user")
    fun listSessions(): ResultVo<List<MpSessionResponse>> {
        val user = SecurityUtils.getCurrentUser()
            ?: return ResultVo.error("User not logged in")
        return ResultVo.success(mpSessionService.listSessions(user.id))
    }

    @PostMapping
    @Operation(summary = "Create session", description = "Create a new mobile session")
    fun createSession(
        @Valid @RequestBody request: MpCreateSessionRequest,
    ): ResultVo<MpSessionResponse> {
        val user = SecurityUtils.getCurrentUser()
            ?: return ResultVo.error("User not logged in")
        return ResultVo.success(mpSessionService.createSession(user.id, user.tenantId, request))
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update session", description = "Update session name")
    fun updateSession(
        @Parameter(description = "Session ID") @PathVariable("id") id: Long,
        @Valid @RequestBody request: MpUpdateSessionRequest,
    ): ResultVo<MpSessionResponse> {
        val user = SecurityUtils.getCurrentUser()
            ?: return ResultVo.error("User not logged in")
        return ResultVo.success(mpSessionService.updateSession(user.id, id, request))
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete session", description = "Delete a mobile session and its messages")
    fun deleteSession(
        @Parameter(description = "Session ID") @PathVariable("id") id: Long,
    ): ResultVo<Void> {
        val user = SecurityUtils.getCurrentUser()
            ?: return ResultVo.error("User not logged in")
        mpSessionService.deleteSession(user.id, id)
        return ResultVo.success()
    }
}
