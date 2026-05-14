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
 * Token management controller
 * Handles token refresh and other operations
 *
 * @author vipamp
 * @since 2026-05-01
 */
@Tag(name = "Token Management", description = "Token refresh and other APIs")
@RestController
@RequestMapping("/api/auth")
class TokenController(
    private val jwtUtil: JwtUtil,
    private val userTenantService: UserTenantService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Refresh token
     * Inherits current tenant context
     */
    @PostMapping("/refresh-token")
    @Operation(summary = "Refresh token", description = "Refresh JWT token, inherits current tenant context")
    fun refreshToken(): ResultVo<Map<String, Any>> {
        return try {
            val currentUser = SecurityUtils.getCurrentUser()
                ?: return ResultVo.error("User not logged in")

            // Get tenant ID from current request (set by TenantInterceptor)
            val currentToken = getCurrentToken()
            val tenantId = if (currentToken != null) {
                jwtUtil.getTenantIdFromToken(currentToken)
            } else {
                null
            }

            // Generate new token, inheriting tenant ID
            val newToken = jwtUtil.generateToken(
                currentUser.id,
                currentUser.username,
                tenantId,
                currentUser.isAdmin,
            )

            log.info("Token refreshed successfully, userId: {}, tenantId: {}", currentUser.id, tenantId)

            ResultVo.success(
                mapOf(
                    "accessToken" to newToken,
                    "tenantId" to tenantId,
                    "expiresIn" to jwtUtil.getExpirationTime() / 1000,
                ),
            )
        } catch (e: Exception) {
            log.error("Failed to refresh token", e)
            ResultVo.error(e.message ?: "Failed to refresh token")
        } as ResultVo<Map<String, Any>>
    }

    /**
     * Get token from current request
     */
    private fun getCurrentToken(): String? = try {
        val request = org.springframework.web.context.request.RequestContextHolder
            .getRequestAttributes() as? org.springframework.web.context.request.ServletRequestAttributes
        val bearerToken = request?.request?.getHeader("Authorization")
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            bearerToken.substring(7)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}
