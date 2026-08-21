package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.LoginRequest
import com.agnetix.harnax.admin.dto.LoginResponse
import com.agnetix.harnax.admin.dto.LoginResponse.UserInfo
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.ApiKeyService
import com.agnetix.harnax.admin.service.AuthService
import com.agnetix.harnax.admin.service.CaptchaService
import com.agnetix.harnax.admin.service.SysTokenBlacklistService
import com.agnetix.harnax.admin.service.SysUserService
import com.agnetix.harnax.admin.service.UserTenantService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.SysUserMapper
import com.agnetix.harnax.mapper.TenantMapper
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
    private val tenantMapper: TenantMapper,
    private val messageUtil: MessageUtil,
    private val apiKeyService: ApiKeyService,
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
        val userTenants = userTenantService.getUserTenants(user.id)

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

        // 7. Get the user's permanent router API key (no longer creates new key per login)
        // Self-heal: if the permanent key is missing (e.g. initializer failed or data was cleaned),
        // create one on the fly so the user can still log in.
        val rawKey = apiKeyService.getPermanentRawKey(user.id) ?: run {
            log.warn("Permanent API Key missing for user: {}, creating one on the fly", user.username)
            try {
                apiKeyService.createPermanentKeyForUser(user.id, user.username, user.tenantId).rawKey
            } catch (e: Exception) {
                // Possibly created concurrently by another login/startup initializer: retry lookup once
                log.error("Failed to create permanent API Key for user: {}, error: {}", user.username, e.message)
                apiKeyService.getPermanentRawKey(user.id)
                    ?: throw BizException("Permanent API Key not found for user: ${user.username}")
            }
        }

        val response = LoginResponse.builder()
            .accessToken(accessToken)
            .tokenType("Bearer")
            .expiresIn(jwtUtil.getExpirationTime() / 1000) // Convert to seconds
            .expiresAt(expiresAt)
            .userInfo(userInfo)
            .tenants(userTenants)
            .currentTenantId(defaultTenantId)
            .routerApiKey(rawKey)
            .build()

        // 8. Update user's last login time
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

    override fun mobileLogin(request: LoginRequest): LoginResponse {
        log.info("Mobile login, username: {}", request.username)

        // 1. Validate username and password (no captcha for mobile)
        val user: SysUser = sysUserService.getByUsername(request.username)
            ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        if (!BCrypt.checkpw(request.password, user.password)) {
            throw BizException(messageUtil.getMessage("error.user.invalid_credentials"))
        }

        // 2. Check user status
        if (user.status == 0) {
            throw BizException(messageUtil.getMessage("error.user.disabled"))
        }

        // 3. Check tenant
        val userTenants = userTenantService.getUserTenants(user.id)
        if (userTenants.isEmpty() && user.isAdmin != 1) {
            throw BizException(messageUtil.getMessage("error.user.no_tenant"))
        }

        // 4. Generate JWT Token
        val defaultTenantId = if (userTenants.isNotEmpty()) userTenants[0].id else null
        val accessToken = jwtUtil.generateToken(user.id, user.username, defaultTenantId, user.isAdmin)
        val expiresAt = System.currentTimeMillis() + jwtUtil.getExpirationTime()

        val userInfo = UserInfo.builder()
            .userId(user.id).username(user.username).nickname(user.nickname)
            .avatar(user.avatar).email(user.email).phone(user.phone)
            .gender(user.gender).isAdmin(user.isAdmin).build()

        val response = LoginResponse.builder()
            .accessToken(accessToken).tokenType("Bearer")
            .expiresIn(jwtUtil.getExpirationTime() / 1000)
            .expiresAt(expiresAt).userInfo(userInfo)
            .tenants(userTenants).currentTenantId(defaultTenantId).build()

        // 5. Update last login time
        try {
            sysUserMapper.updateLastLoginTime(user.id, LocalDateTime.now())
        } catch (e: Exception) {
            log.error("Failed to update mobile user login time: {}", e.message)
        }

        log.info("Mobile login successful, userId: {}, username: {}", user.id, user.username)
        return response
    }

    override fun cliLogin(request: LoginRequest): LoginResponse {
        log.info("CLI login, username: {}", request.username)

        // 1. Validate username and password (no captcha — CLI has no interactive UI).
        // CLI sends the plain password; the DB stores BCrypt(SHA-256(plain)),
        // so hash server-side to match the web-login verification chain.
        val user: SysUser = sysUserService.getByUsername(request.username)
            ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        if (!BCrypt.checkpw(sha256Hex(request.password ?: ""), user.password)) {
            throw BizException(messageUtil.getMessage("error.user.invalid_credentials"))
        }

        // 2. Check user status
        if (user.status == 0) {
            throw BizException(messageUtil.getMessage("error.user.disabled"))
        }

        // 3. Check tenant
        val userTenants = userTenantService.getUserTenants(user.id)
        if (userTenants.isEmpty() && user.isAdmin != 1) {
            throw BizException(messageUtil.getMessage("error.user.no_tenant"))
        }

        // 4. Generate JWT Token
        val defaultTenantId = if (userTenants.isNotEmpty()) userTenants[0].id else null
        val accessToken = jwtUtil.generateToken(user.id, user.username, defaultTenantId, user.isAdmin)
        val expiresAt = System.currentTimeMillis() + jwtUtil.getExpirationTime()

        val userInfo = UserInfo.builder()
            .userId(user.id).username(user.username).nickname(user.nickname)
            .avatar(user.avatar).email(user.email).phone(user.phone)
            .gender(user.gender).isAdmin(user.isAdmin).build()

        // 5. Get the user's permanent router API key (self-heal like web login)
        val rawKey = apiKeyService.getPermanentRawKey(user.id) ?: run {
            log.warn("Permanent API Key missing for user: {}, creating one on the fly", user.username)
            try {
                apiKeyService.createPermanentKeyForUser(user.id, user.username, user.tenantId).rawKey
            } catch (e: Exception) {
                log.error("Failed to create permanent API Key for user: {}, error: {}", user.username, e.message)
                apiKeyService.getPermanentRawKey(user.id)
                    ?: throw BizException("Permanent API Key not found for user: ${user.username}")
            }
        }

        val response = LoginResponse.builder()
            .accessToken(accessToken).tokenType("Bearer")
            .expiresIn(jwtUtil.getExpirationTime() / 1000)
            .expiresAt(expiresAt).userInfo(userInfo)
            .tenants(userTenants).currentTenantId(defaultTenantId)
            .routerApiKey(rawKey).build()

        // 6. Update last login time
        try {
            sysUserMapper.updateLastLoginTime(user.id, LocalDateTime.now())
        } catch (e: Exception) {
            log.error("Failed to update CLI user login time: {}", e.message)
        }

        log.info("CLI login successful, userId: {}, username: {}", user.id, user.username)
        return response
    }

    /**
     * SHA-256 lowercase hex — matches the web frontend's CryptoJS.SHA256(pwd).toString().
     */
    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
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

                // Invalidate MP router API keys (legacy cleanup - no longer needed with permanent keys)
                // Permanent keys are never disabled on logout

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
