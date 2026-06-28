package com.agnetix.harnax.admin.controller.mp

import com.agnetix.harnax.admin.dto.CaptchaResponse
import com.agnetix.harnax.admin.dto.LoginRequest
import com.agnetix.harnax.admin.dto.mp.MpLoginResponse
import com.agnetix.harnax.admin.service.AuthService
import com.agnetix.harnax.admin.service.CaptchaService
import com.agnetix.harnax.admin.service.mp.MpAuthService
import com.agnetix.harnax.common.dto.ResultVo
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

@Tag(name = "MP Auth", description = "Mobile authentication APIs")
@RestController
@RequestMapping("/api/admin/mp/auth")
class MpAuthController(
    private val authService: AuthService,
    private val captchaService: CaptchaService,
    private val mpAuthService: MpAuthService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/login")
    @Operation(summary = "Mobile login", description = "Username/password login returning JWT + auto-generated routerApiKey")
    fun login(@Valid @RequestBody request: LoginRequest): ResultVo<MpLoginResponse> = ResultVo.success(mpAuthService.login(request))

    @PostMapping("/logout")
    @Operation(summary = "Mobile logout", description = "User logout for mobile app")
    fun logout(): ResultVo<Void> {
        authService.logout()
        return ResultVo.success()
    }

    @GetMapping("/captcha")
    @Operation(summary = "Get captcha", description = "Get graphical captcha image for mobile app")
    fun getCaptcha(): ResultVo<CaptchaResponse> {
        try {
            return ResultVo.success(captchaService.generateCaptcha())
        } catch (e: Exception) {
            log.error("Failed to get captcha", e)
            return ResultVo.error(e.message ?: "Failed to get captcha")
        }
    }
}
