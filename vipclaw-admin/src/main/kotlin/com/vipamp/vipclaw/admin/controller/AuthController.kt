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
 * 认证控制器
 */
@Tag(name = "认证管理", description = "用户登录、登出、验证码等接口")
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
     * 用户登录
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "用户名密码登录")
    fun login(@Valid @RequestBody request: @Valid LoginRequest): ResultVo<LoginResponse> = ResultVo.success(authService.login(request))

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    @Operation(summary = "退出登录", description = "用户退出登录")
    fun logout(): ResultVo<Void> {
        authService.logout()
        return ResultVo.success()
    }

    /**
     * 获取验证码
     */
    @GetMapping("/captcha")
    @Operation(summary = "获取验证码", description = "获取图形验证码图片")
    fun getCaptcha(): ResultVo<CaptchaResponse> {
        try {
            return ResultVo.success(captchaService.generateCaptcha())
        } catch (e: Exception) {
            log.error("获取验证码失败", e)
            return ResultVo.error(e.message ?: "获取验证码失败")
        }
    }

    /**
     * 获取当前版本支持的登录方式
     */
    @GetMapping("/login-methods")
    @Operation(summary = "获取登录方式", description = "根据当前版本获取支持的登录方式")
    fun getLoginMethods(): ResultVo<Map<String, Any>> {
        val methods = mutableListOf("username") // 所有版本都支持用户名登录

        // 企业版和公网版支持手机号和邮箱登录
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
     * 获取用户所属租户列表
     */
    @GetMapping("/tenants")
    @Operation(summary = "获取租户列表", description = "获取当前用户所属的所有租户")
    fun getUserTenants(): ResultVo<List<TenantResponse>> {
        return try {
            val currentUser = SecurityUtils.getCurrentUser()
                ?: return ResultVo.error("用户未登录")

            val tenants = userTenantService.getUserTenants(currentUser.id)
            ResultVo.success(tenants)
        } catch (e: Exception) {
            log.error("获取租户列表失败", e)
            ResultVo.error(e.message ?: "获取租户列表失败")
        }
    }

    /**
     * 切换租户
     */
    @PostMapping("/switch-tenant")
    @Operation(summary = "切换租户", description = "切换到指定租户，返回新的Token")
    fun switchTenant(
        @Valid @RequestBody request: SwitchTenantRequest,
    ): ResultVo<Map<String, Any>> {
        return try {
            val currentUser = SecurityUtils.getCurrentUser()
                ?: return ResultVo.error("用户未登录")

            // 验证用户是否属于该租户
            val isInTenant = userTenantService.isUserInTenant(currentUser.id, request.tenantId)
            if (!isInTenant && currentUser.isAdmin != 1) {
                return ResultVo.error("无权访问该租户")
            }

            // 生成新Token
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
            log.error("切换租户失败", e)
            ResultVo.error(e.message ?: "切换租户失败")
        }
    }
}
