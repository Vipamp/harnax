package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.LoginRequest
import com.vipamp.vipclaw.admin.dto.LoginResponse
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.service.AuthService
import com.vipamp.vipclaw.admin.service.CaptchaService
import com.vipamp.vipclaw.admin.service.SysTokenBlacklistService
import com.vipamp.vipclaw.admin.service.SysUserService
import com.vipamp.vipclaw.admin.util.JwtUtil
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
    private val tokenBlacklistService: SysTokenBlacklistService
) : AuthService {

    private val log = LoggerFactory.getLogger(AuthServiceImpl::class.java)

    override fun login(request: LoginRequest): LoginResponse {
        log.info("用户登录，username: {}", request.username)

        // 1. 验证用户名和密码
        val user = sysUserService.getByUsername(request.username)
            ?: throw BizException("用户名或密码错误")

        // TODO: 实际项目中应该使用加密后的密码进行比对
        if (request.password != user.password) {
            throw BizException("用户名或密码错误")
        }

        // 2. 校验验证码
        val captcha = request.captcha
        val captchaKey = request.captchaKey
        if (captcha.isNullOrEmpty()) {
            throw BizException("请输入验证码")
        }
        if (captchaKey.isNullOrEmpty()) {
            throw BizException("验证码 key 不能为空")
        }
        if (!captchaService.validateCaptcha(captchaKey, captcha)) {
            throw BizException("验证码错误，请重新输入")
        }

        // 3. 检查用户状态
        if (user.status == 0) {
            throw BizException("用户已被禁用，请联系管理员")
        }

        // 4. 生成 JWT Token
        val accessToken = jwtUtil.generateToken(user.id!!, user.username!!)

        // 5. 计算过期时间戳
        val expiresAt = System.currentTimeMillis() + jwtUtil.getExpirationTime()

        // 6. 构建响应
        val userInfo = LoginResponse.UserInfo.builder()
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
            .build()

        log.info("用户登录成功，userId: {}, username: {}", user.id, user.username)
        return response
    }

    override fun logout() {
        // 获取当前请求（需要从 RequestContextHolder 中获取）
        val token = getCurrentToken()
        if (token != null) {
            try {
                // 解析 Token 获取用户信息
                val userId = jwtUtil.getUserIdFromToken(token)
                val username = jwtUtil.getUsernameFromToken(token)

                // 计算过期时间
                val expireTime = LocalDateTime.now().plusNanos(jwtUtil.expirationTime * 1000000)

                // 将 Token 加入 MySQL 黑名单
                tokenBlacklistService.addToBlacklist(token, username, userId, expireTime, "logout")
                log.info("用户退出登录，userId: {}, username: {}", userId, username)
            } catch (e: Exception) {
                // Token 无效或已过期，直接记录退出
                log.warn("退出登录时 Token 无效或已过期：${e.message}")
            }
        } else {
            log.info("用户退出登录（未携带 Token）")
        }
    }

    /**
     * 从当前请求中获取 Token
     */
    private fun getCurrentToken(): String? {
        return try {
            val request = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request
            val bearerToken = request.getHeader("Authorization")
            if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
                bearerToken.substring(7)
            } else {
                null
            }
        } catch (e: Exception) {
            log.error("获取当前 Token 失败：${e.message}")
            null
        }
    }
}
