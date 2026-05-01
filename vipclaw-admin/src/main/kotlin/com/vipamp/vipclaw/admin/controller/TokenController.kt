package com.vipamp.vipclaw.admin.controller

import com.vipamp.vipclaw.admin.dto.ResultVo
import com.vipamp.vipclaw.admin.security.SecurityUtils
import com.vipamp.vipclaw.admin.service.UserTenantService
import com.vipamp.vipclaw.admin.util.JwtUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Token管理控制器
 * 处理Token刷新等操作
 *
 * @author vipamp
 * @since 2026-05-01
 */
@Tag(name = "Token管理", description = "Token刷新等接口")
@RestController
@RequestMapping("/api/auth")
class TokenController(
    private val jwtUtil: JwtUtil,
    private val userTenantService: UserTenantService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 刷新Token
     * 继承当前租户上下文
     */
    @PostMapping("/refresh-token")
    @Operation(summary = "刷新Token", description = "刷新JWT Token，继承当前租户上下文")
    fun refreshToken(): ResultVo<Map<String, Any>> {
        return try {
            val currentUser = SecurityUtils.getCurrentUser()
                ?: return ResultVo.error("用户未登录")

            // 从当前请求中获取租户ID（由TenantInterceptor设置）
            val currentToken = getCurrentToken()
            val tenantId = if (currentToken != null) {
                jwtUtil.getTenantIdFromToken(currentToken)
            } else {
                null
            }

            // 生成新Token，继承租户ID
            val newToken = jwtUtil.generateToken(
                currentUser.id,
                currentUser.username,
                tenantId,
                currentUser.isAdmin
            )

            log.info("Token刷新成功，userId: {}, tenantId: {}", currentUser.id, tenantId)

            ResultVo.success(mapOf(
                "accessToken" to newToken,
                "tenantId" to tenantId,
                "expiresIn" to jwtUtil.getExpirationTime() / 1000
            ))
        } catch (e: Exception) {
            log.error("Token刷新失败", e)
            ResultVo.error(e.message ?: "Token刷新失败")
        } as ResultVo<Map<String, Any>>
    }

    /**
     * 从当前请求中获取Token
     */
    private fun getCurrentToken(): String? {
        return try {
            val request = org.springframework.web.context.request.RequestContextHolder
                .getRequestAttributes() as? org.springframework.web.context.request.ServletRequestAttributes
            val bearerToken = request?.request?.getHeader("Authorization")
            if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                bearerToken.substring(7)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
