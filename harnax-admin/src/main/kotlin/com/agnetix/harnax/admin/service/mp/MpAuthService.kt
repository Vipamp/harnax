package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.dto.LoginRequest
import com.agnetix.harnax.admin.dto.mp.MpLoginResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.ApiKeyService
import com.agnetix.harnax.admin.service.UserTenantService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.mapper.SysUserMapper
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class MpAuthService(
    private val sysUserMapper: SysUserMapper,
    private val userTenantService: UserTenantService,
    private val jwtUtil: JwtUtil,
    private val apiKeyService: ApiKeyService,
    private val messageUtil: MessageUtil,
) {

    private val log = LoggerFactory.getLogger(MpAuthService::class.java)

    @Value("\${harnax.router.url:http://localhost:8081}")
    private var routerUrl: String = "http://localhost:8081"

    @Value("\${harnax.router.external-url:}")
    private var routerExternalUrl: String = ""

    fun login(request: LoginRequest): MpLoginResponse {
        log.info("MP login, username: {}", request.username)

        if (request.password.isNullOrBlank()) {
            throw BizException(messageUtil.getMessage("error.user.invalid_credentials"))
        }

        val user = sysUserMapper.selectByUsername(request.username)
            ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        if (!BCrypt.checkpw(request.password, user.password)) {
            throw BizException(messageUtil.getMessage("error.user.invalid_credentials"))
        }

        if (user.status == 0) {
            throw BizException(messageUtil.getMessage("error.user.disabled"))
        }

        val userTenants = userTenantService.getUserTenants(user.id)
        if (userTenants.isEmpty() && user.isAdmin != 1) {
            throw BizException(messageUtil.getMessage("error.user.no_tenant"))
        }

        val defaultTenantId = if (userTenants.isNotEmpty()) userTenants[0].id else null
        val accessToken = jwtUtil.generateToken(user.id, user.username, defaultTenantId, user.isAdmin)

        // Get user's permanent API key (no longer creates new key per login)
        val rawKey = apiKeyService.getPermanentRawKey(user.id)
            ?: throw BizException("Permanent API Key not found for user: ${user.username}")

        try {
            sysUserMapper.updateLastLoginTime(user.id, LocalDateTime.now())
        } catch (e: Exception) {
            log.error("Failed to update MP user login time: {}", e.message)
        }

        log.info("MP login successful, userId: {}, username: {}", user.id, user.username)

        // Use external URL if configured (for frontend/mobile), otherwise use internal URL
        val actualRouterUrl = if (routerExternalUrl.isNotBlank()) routerExternalUrl else routerUrl

        return MpLoginResponse(
            accessToken = accessToken,
            routerApiKey = rawKey,
            routerUrl = actualRouterUrl,
            expiresIn = jwtUtil.getExpirationTime() / 1000,
            userInfo = MpLoginResponse.UserInfo(
                userId = user.id,
                username = user.username,
                nickname = user.nickname,
            ),
        )
    }
}
