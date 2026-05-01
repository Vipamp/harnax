package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.config.EditionUtil
import com.vipamp.vipclaw.admin.dto.LoginRequest
import com.vipamp.vipclaw.admin.dto.LoginResponse
import com.vipamp.vipclaw.admin.dto.LoginResponse.UserInfo
import com.vipamp.vipclaw.admin.dto.response.TenantResponse
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
 * 认证服务实现类
 *
 * TODO: 实际项目中需要集成 JWT、Redis 等实现真正的认证和令牌管理
 * 这里提供简化的实现用于演示
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
    private val messageUtil: MessageUtil
) : AuthService {

    private val log: Logger = LoggerFactory.getLogger(AuthServiceImpl::class.java)

    override fun login(request: LoginRequest): LoginResponse {
        log.info("用户登录，username: {}", request.username)

        // 1. 验证用户名和密码
        val user: SysUser = sysUserService.getByUsername(request.username) ?: throw BizException(messageUtil.getMessage("error.user.notfound"))

        // 前端使用 SHA-256 加密密码，数据库中存储的是 BCrypt(SHA-256(明文密码))
        // 使用 BCrypt 验证前端传来的 SHA-256 密码
        if (!BCrypt.checkpw(request.password, user.password)) {
            throw BizException(messageUtil.getMessage("error.user.invalid_credentials"))
        }

        // 2. 校验验证码
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

        // 3. 检查用户状态
        if (user.status == 0) {
            throw BizException(messageUtil.getMessage("error.user.disabled"))
        }

        // 4. 检查用户是否属于任何租户
        var userTenants = userTenantService.getUserTenants(user.id)
        
        // 个人版：如果用户没有租户，自动关联到默认租户（id=1）
        if (userTenants.isEmpty() && editionUtil.isPersonal()) {
            log.info("[个人版] 用户 {} 没有租户，自动关联到默认租户", user.username)
            
            // 检查默认租户是否存在
            val defaultTenant = tenantMapper.selectById(1)
            if (defaultTenant != null) {
                // 自动将用户添加到默认租户
                userTenantService.addUserToTenant(1, user.id, "member", "system")
                log.info("[个人版] 已将用户 {} 自动添加到默认租户", user.username)
                
                // 重新获取租户列表
                userTenants = userTenantService.getUserTenants(user.id)
            } else {
                log.warn("[个人版] 默认租户不存在，无法自动关联")
            }
        }
        
        if (userTenants.isEmpty() && user.isAdmin != 1) {
            throw BizException(messageUtil.getMessage("error.user.no_tenant"))
        }

        // 5. 生成 JWT Token（使用第一个租户ID）
        val defaultTenantId = if (userTenants.isNotEmpty()) userTenants[0].id else null
        val accessToken: String = jwtUtil.generateToken(user.id, user.username, defaultTenantId, user.isAdmin)

        // 5. 计算过期时间戳
        val expiresAt: Long = System.currentTimeMillis() + jwtUtil.getExpirationTime()

        // 6. 构建响应
        log.info("用户信息 - id: {}, username: {}, isAdmin: {}", user.id, user.username, user.isAdmin)
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
            .expiresIn(jwtUtil.getExpirationTime() / 1000) // 转换为秒
            .expiresAt(expiresAt)
            .userInfo(userInfo)
            .tenants(userTenants)
            .currentTenantId(defaultTenantId)
            .build()

        // 7. 更新用户最近一次登录时间
        try {
            val now = LocalDateTime.now()
            sysUserMapper.updateLastLoginTime(user.id, now)
            log.info("更新用户登录时间成功，userId: {}, loginTime: {}", user.id, now)
        } catch (e: Exception) {
            log.error("更新用户登录时间失败，userId: {}, error: {}", user.id, e.message)
        }

        log.info("用户登录成功，userId: {}, username: {}", user.id, user.username)
        return response
    }

    override fun logout() {
        // 获取当前请求（需要从 RequestContextHolder 中获取）
        val token = getCurrentToken()
        if (token != null) {
            try {
                // 解析 Token 获取用户信息
                val userId: Long = jwtUtil.getUserIdFromToken(token)
                val username: String = jwtUtil.getUsernameFromToken(token)

                // 计算过期时间
                val expireTime = LocalDateTime.now().plusNanos(jwtUtil.getExpirationTime() * 1000000)

                // 将 Token 加入 MySQL 黑名单
                tokenBlacklistService.addToBlacklist(token, username, userId, expireTime, "logout")
                log.info("用户退出登录，userId: {}, username: {}", userId, username)
            } catch (e: Exception) {
                // Token 无效或已过期，直接记录退出
                log.warn("退出登录时 Token 无效或已过期：{}", e.message)
            }
        } else {
            log.info("用户退出登录（未携带 Token）")
        }
    }

    /**
     * 从当前请求中获取 Token
     */
    private fun getCurrentToken(): String? {
        try {
            val request = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).getRequest()
            val bearerToken = request.getHeader("Authorization")
            if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                return bearerToken.substring(7)
            }
        } catch (e: Exception) {
            log.error("获取当前 Token 失败：{}", e.message)
        }
        return null
    }
}
