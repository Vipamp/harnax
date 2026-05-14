package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.config.EditionUtil
import com.vipamp.vipclaw.admin.dto.CaptchaResponse
import com.vipamp.vipclaw.admin.dto.LoginRequest
import com.vipamp.vipclaw.admin.dto.LoginResponse
import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.dto.request.SwitchTenantRequest
import com.vipamp.vipclaw.admin.dto.response.TenantResponse
import com.vipamp.vipclaw.admin.security.SecurityUtils
import com.vipamp.vipclaw.admin.service.AuthService
import com.vipamp.vipclaw.admin.service.CaptchaService
import com.vipamp.vipclaw.admin.service.UserTenantService
import com.vipamp.vipclaw.admin.util.JwtUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * Authentication controller
 */
@Tag(name = "Authentication", description = "User login, logout, captcha and other APIs")
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val captchaService: CaptchaService,
    private val editionUtil: EditionUtil,
    private val userTenantService: UserTenantService,
    private val jwtUtil: JwtUtil,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * User login
     */
    @PostMapping("/login")
    @Operation(summary = "User login", description = "Username and password login")
    fun login(@Valid @RequestBody request: @Valid LoginRequest): ResultVo<LoginResponse> = ResultVo.success(authService.login(request))

    /**
     * Logout
     */
    @PostMapping("/logout")
    @Operation(summary = "Logout", description = "User logout")
    fun logout(): ResultVo<Void> {
        authService.logout()
        return ResultVo.success()
    }

    /**
     * Get captcha
     */
    @GetMapping("/captcha")
    @Operation(summary = "Get captcha", description = "Get graphical captcha image")
    fun getCaptcha(): ResultVo<CaptchaResponse> {
        try {
            return ResultVo.success(captchaService.generateCaptcha())
        } catch (e: Exception) {
            log.error("Failed to get captcha", e)
            return ResultVo.error(e.message ?: "Failed to get captcha")
        }
    }

    /**
     * Get login methods supported by current edition
     */
    @GetMapping("/login-methods")
    @Operation(summary = "Get login methods", description = "Get supported login methods by current edition")
    fun getLoginMethods(): ResultVo<Map<String, Any>> {
        val methods = mutableListOf("username") // All editions support username login

        // Enterprise and public editions support phone and email login
        if (editionUtil.isEnterprise() || editionUtil.isPublic()) {
            methods.add("phone")
            methods.add("email")
        }

        return ResultVo.success(
            mapOf(
                "methods" to methods,
            ),
        )
    }

    /**
     * Get tenant list for current user
     */
    @GetMapping("/tenants")
    @Operation(summary = "Get tenant list", description = "Get all tenants for current user")
    fun getUserTenants(): ResultVo<List<TenantResponse>> {
        return try {
            val currentUser = SecurityUtils.getCurrentUser()
                ?: return ResultVo.error("User not logged in")

            val tenants = userTenantService.getUserTenants(currentUser.id)
            ResultVo.success(tenants)
        } catch (e: Exception) {
            log.error("Failed to get tenant list", e)
            ResultVo.error(e.message ?: "Failed to get tenant list")
        }
    }

    /**
     * Switch tenant
     */
    @PostMapping("/switch-tenant")
    @Operation(summary = "Switch tenant", description = "Switch to specified tenant and return new token")
    fun switchTenant(
        @Valid @RequestBody request: SwitchTenantRequest,
    ): ResultVo<Map<String, Any>> {
        return try {
            val currentUser = SecurityUtils.getCurrentUser()
                ?: return ResultVo.error("User not logged in")

            // Verify if user belongs to this tenant
            val isInTenant = userTenantService.isUserInTenant(currentUser.id, request.tenantId)
            if (!isInTenant && currentUser.isAdmin != 1) {
                return ResultVo.error("No access to this tenant")
            }

            // Generate new token
            val newToken = jwtUtil.generateToken(
                currentUser.id,
                currentUser.username,
                request.tenantId,
                currentUser.isAdmin,
            )

            ResultVo.success(
                mapOf(
                    "accessToken" to newToken,
                    "tenantId" to request.tenantId,
                ),
            )
        } catch (e: Exception) {
            log.error("Failed to switch tenant", e)
            ResultVo.error(e.message ?: "Failed to switch tenant")
        }
    }
}
