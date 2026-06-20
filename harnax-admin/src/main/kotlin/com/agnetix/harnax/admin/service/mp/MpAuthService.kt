package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.dto.LoginRequest
import com.agnetix.harnax.admin.dto.mp.MpLoginResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.UserTenantService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.ApiKeyEntity
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.SysUserMapper
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.Base64

@Service
class MpAuthService(
    private val sysUserMapper: SysUserMapper,
    private val userTenantService: UserTenantService,
    private val jwtUtil: JwtUtil,
    private val apiKeyMapper: ApiKeyMapper,
    private val messageUtil: MessageUtil,
) {

    private val log = LoggerFactory.getLogger(MpAuthService::class.java)
    private val secureRandom = SecureRandom()

    @Value("\${harnax.router.url:http://localhost:8081}")
    private var routerUrl: String = "http://localhost:8081"

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

        val rawKey = generateRawKey()
        val keyHash = sha256(rawKey)
        val keyPrefix = rawKey.substring(0, 12) + "..." + rawKey.substring(rawKey.length - 4)
        val expiresAt = LocalDateTime.now().plusSeconds(jwtUtil.getExpirationTime() / 1000)

        val apiKey = ApiKeyEntity().apply {
            name = "mp_router_${user.username}_${System.currentTimeMillis()}"
            this.keyHash = keyHash
            this.keyPrefix = keyPrefix
            scopes = "api:chat"
            tenantId = defaultTenantId
            rateLimit = 120
            enabled = 1
            this.expiresAt = expiresAt
            creator = user.username
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        apiKeyMapper.insert(apiKey)

        try {
            sysUserMapper.updateLastLoginTime(user.id, LocalDateTime.now())
        } catch (e: Exception) {
            log.error("Failed to update MP user login time: {}", e.message)
        }

        log.info("MP login successful, userId: {}, username: {}", user.id, user.username)
        return MpLoginResponse(
            accessToken = accessToken,
            routerApiKey = rawKey,
            routerUrl = routerUrl,
            expiresIn = jwtUtil.getExpirationTime() / 1000,
            userInfo = MpLoginResponse.UserInfo(
                userId = user.id,
                username = user.username,
                nickname = user.nickname,
            ),
        )
    }

    private fun generateRawKey(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return "hnx_sk_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
