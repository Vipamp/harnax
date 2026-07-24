package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.service.impl.WechatLoginService
import com.agnetix.harnax.admin.service.impl.WechatLoginStatus
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * WeChat (iLink) QR-code login controller.
 *
 * Drives the scan-login flow from the admin console:
 * 1. POST /login  → returns a base64 PNG QR code for the user to scan
 * 2. GET  /login/status → frontend polls until LOGGED_IN / EXPIRED / ERROR
 * 3. POST /login/cancel → abort an in-progress login
 *
 * On success the obtained credentials are written into the channel's
 * configJson; the channel-service then connects without scanning again.
 */
@RestController
@RequestMapping("/api/admin/channels/{id}/wechat")
@Tag(name = "WeChat Login", description = "WeChat iLink QR-code login APIs")
class WechatLoginController(
    private val wechatLoginService: WechatLoginService,
) {
    private val log = LoggerFactory.getLogger(WechatLoginController::class.java)

    @PostMapping("/login")
    @Operation(summary = "Start WeChat QR login", description = "Generate a QR code (base64 PNG) for the user to scan")
    fun startLogin(
        @Parameter(description = "Channel ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<String> = try {
        ResultVo.success(wechatLoginService.startLogin(id))
    } catch (e: Exception) {
        log.error("Failed to start WeChat login for channel {}", id, e)
        ResultVo.error(e.message ?: "Failed to start WeChat login")
    }

    @GetMapping("/login/status")
    @Operation(summary = "Query WeChat login status", description = "Poll the login status; persists credentials on success")
    fun queryStatus(
        @Parameter(description = "Channel ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<WechatLoginStatus> = try {
        ResultVo.success(wechatLoginService.queryStatus(id))
    } catch (e: Exception) {
        log.error("Failed to query WeChat login status for channel {}", id, e)
        ResultVo.error(e.message ?: "Failed to query WeChat login status")
    }

    @PostMapping("/login/cancel")
    @Operation(summary = "Cancel WeChat login", description = "Abort an in-progress QR login")
    fun cancelLogin(
        @Parameter(description = "Channel ID") @PathVariable(name = "id") id: Long,
    ): ResultVo<Void> = try {
        wechatLoginService.cancelLogin(id)
        ResultVo.success()
    } catch (e: Exception) {
        log.error("Failed to cancel WeChat login for channel {}", id, e)
        ResultVo.error(e.message ?: "Failed to cancel WeChat login")
    }
}
