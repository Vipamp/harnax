package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.config.EditionUtil
import com.vipamp.vipclaw.admin.dto.LoginRequest
import com.vipamp.vipclaw.admin.dto.LoginResponse
import com.vipamp.vipclaw.admin.dto.LoginResponse.UserInfo
import com.vipamp.vipclaw.admin.entity.SysUser
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.i18n.MessageUtil
import com.vipamp.vipclaw.admin.mapper.SysUserMapper
import com.vipamp.vipclaw.admin.mapper.TenantMapper
import com.vipamp.vipclaw.admin.service.AuthService
import com.vipamp.vipclaw.admin.service.CaptchaService
import com.vipamp.vipclaw.admin.service.SysTokenBlacklistService
import com.vipamp.vipclaw.admin.service.SysUserService
import com.vipamp.vipclaw.admin.service.UserTenantService
import com.vipamp.vipclaw.admin.util.JwtUtil
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

/**
 * Authentication service implementation
 *
 * TODO: In actual project, need to integrate JWT, Redis, etc. for real authentication and token management
 * This provides a simplified implementation for demonstration
 */
@Service
class AuthServiceImpl(
    private val sysUserService: SysUserService,
    private val captchaService: CaptchaService,
    private val jwtUtil: JwtUtil,
    private val tokenBlacklistService: SysTokenBlacklistService,
    private val sysUserMapper: SysUserMapper,
    private val userTenantService: UserTenantService,
    private val editionUtil: EditionUtil,
    private val tenantMapper: TenantMapper,
    private val messageUtil: MessageUtil,
) : AuthService {

    private val log: Logger = LoggerFactory.getLogger(AuthServiceImpl::class.java)

    override fun login(request: LoginRequest): LoginResponse {
        log.info("User login, username: {}", request.username)

        // 1. Validate username and password
        val user: SysUser = sysUserService.getByUsername(request.username) ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        // Frontend uses SHA-256 to encrypt password, database stores BCrypt(SHA-256(plain password))
        // Use BCrypt to verify the SHA-256 password from frontend
        if (!BCrypt.checkpw(request.password, user.password)) {
            throw BizException(messageUtil.getMessage("error.user.invalid_credentials"))
        }

        // 2. Validate captcha
        val captcha = request.captcha
        val captchaKey = request.captchaKey
        if (captcha == null || captcha.trim { it <= ' ' }.isEmpty()) {
            throw BizException(messageUtil.getMessage("error.captcha.required"))
        }
        if (captchaKey == null || captchaKey.trim { it <= ' ' }.isEmpty()) {
            throw BizException(messageUtil.getMessage("error.captcha.key_required"))
        }
        if (!captchaService.validateCaptcha(captchaKey, captcha)) {
            throw BizException(messageUtil.getMessage("error.captcha.invalid"))
        }

        // 3. Check user status
        if (user.status == 0) {
            throw BizException(messageUtil.getMessage("error.user.disabled"))
        }

        // 4. Check if user belongs to any tenant
        var userTenants = userTenantService.getUserTenants(user.id)

        // Personal edition: If user has no tenant, automatically associate with default tenant (id=1)
        if (userTenants.isEmpty() && editionUtil.isPersonal()) {
            log.info("[Personal Edition] User {} has no tenant, automatically associating with default tenant", user.username)

            // Check if default tenant exists
            val defaultTenant = tenantMapper.selectById(1)
            if (defaultTenant != null) {
                // Automatically add user to default tenant
                userTenantService.addUserToTenant(1, user.id, "member", "system")
                log.info("[Personal Edition] User {} has been automatically added to default tenant", user.username)

                // Re-fetch tenant list
                userTenants = userTenantService.getUserTenants(user.id)
            } else {
                log.warn("[Personal Edition] Default tenant does not exist, cannot auto-associate")
            }
        }

        if (userTenants.isEmpty() && user.isAdmin != 1) {
            throw BizException(messageUtil.getMessage("error.user.no_tenant"))
        }

        // 5. Generate JWT Token (using first tenant ID)
        val defaultTenantId = if (userTenants.isNotEmpty()) userTenants[0].id else null
        val accessToken: String = jwtUtil.generateToken(user.id, user.username, defaultTenantId, user.isAdmin)

        // 5. Calculate expiration timestamp
        val expiresAt: Long = System.currentTimeMillis() + jwtUtil.getExpirationTime()

        // 6. Build response
        log.info("User info - id: {}, username: {}, isAdmin: {}", user.id, user.username, user.isAdmin)
        val userInfo = UserInfo.builder()
            .userId(user.id)
            .username(user.username)
            .nickname(user.nickname)
            .avatar(user.avatar)
            .email(user.email)
            .phone(user.phone)
            .gender(user.gender)
            .isAdmin(user.isAdmin)
            .build()

        val response = LoginResponse.builder()
            .accessToken(accessToken)
            .tokenType("Bearer")
            .expiresIn(jwtUtil.getExpirationTime() / 1000) // Convert to seconds
            .expiresAt(expiresAt)
            .userInfo(userInfo)
            .tenants(userTenants)
            .currentTenantId(defaultTenantId)
            .build()

        // 7. Update user's last login time
        try {
            val now = LocalDateTime.now()
            sysUserMapper.updateLastLoginTime(user.id, now)
            log.info("User login time updated successfully, userId: {}, loginTime: {}", user.id, now)
        } catch (e: Exception) {
            log.error("Failed to update user login time, userId: {}, error: {}", user.id, e.message)
        }

        log.info("User login successful, userId: {}, username: {}", user.id, user.username)
        return response
    }

    override fun logout() {
        // Get current request (need to get from RequestContextHolder)
        val token = getCurrentToken()
        if (token != null) {
            try {
                // Parse Token to get user info
                val userId: Long = jwtUtil.getUserIdFromToken(token)
                val username: String = jwtUtil.getUsernameFromToken(token)

                // Calculate expiration time
                val expireTime = LocalDateTime.now().plusNanos(jwtUtil.getExpirationTime() * 1000000)

                // Add Token to MySQL blacklist
                tokenBlacklistService.addToBlacklist(token, username, userId, expireTime, "logout")
                log.info("User logged out, userId: {}, username: {}", userId, username)
            } catch (e: Exception) {
                // Token is invalid or expired, log out directly
                log.warn("Token is invalid or expired during logout: {}", e.message)
            }
        } else {
            log.info("User logged out (no Token provided)")
        }
    }

    /**
     * Get Token from current request
     */
    private fun getCurrentToken(): String? {
        try {
            val request = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).getRequest()
            val bearerToken = request.getHeader("Authorization")
            if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                return bearerToken.substring(7)
            }
        } catch (e: Exception) {
            log.error("Failed to get current Token: {}", e.message)
        }
        return null
    }
}
