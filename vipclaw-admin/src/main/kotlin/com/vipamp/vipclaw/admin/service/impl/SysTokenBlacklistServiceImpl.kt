package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.entity.SysTokenBlacklist
import com.vipamp.vipclaw.admin.mapper.SysTokenBlacklistMapper
import com.vipamp.vipclaw.admin.service.SysTokenBlacklistService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.security.MessageDigest
import java.time.LocalDateTime

/**
 * Token 黑名单服务实现类
 */
@Service
class SysTokenBlacklistServiceImpl(
    private val tokenBlacklistMapper: SysTokenBlacklistMapper,
) : SysTokenBlacklistService {

    private val log = LoggerFactory.getLogger(SysTokenBlacklistServiceImpl::class.java)

    @Transactional(rollbackFor = [Exception::class])
    override fun addToBlacklist(
        token: String,
        username: String,
        userId: Long,
        expireTime: LocalDateTime,
        reason: String,
    ) {
        try {
            // 计算 Token 的 SHA256 哈希值（避免存储完整 Token）
            val tokenHash = hashToken(token)

            // 获取客户端 IP
            val clientIp = getClientIp()

            // 构建黑名单记录
            val blacklist = SysTokenBlacklist.builder()
                .token(token) // 存储完整 Token（可选，用于审计）
                .tokenHash(tokenHash)
                .username(username)
                .userId(userId)
                .reason(reason)
                .expireTime(expireTime)
                .createTime(LocalDateTime.now())
                .createIp(clientIp)
                .build()

            // 保存到数据库
            tokenBlacklistMapper.insert(blacklist)

            log.info("Token 已加入黑名单，userId: {}, username: {}, reason: {}", userId, username, reason)
        } catch (e: Exception) {
            log.error("添加 Token 到黑名单失败：${e.message}", e)
            // 不抛出异常，避免影响退出流程
        }
    }

    override fun isBlacklisted(token: String): Boolean {
        return try {
            // 计算 Token 的 SHA256 哈希值
            val tokenHash = hashToken(token)

            // 查询是否在黑名单中且未过期
            val blacklist = tokenBlacklistMapper.selectByTokenHash(tokenHash)

            if (blacklist != null) {
                log.debug("Token 在黑名单中，userId: {}", blacklist.userId)
                return true
            }

            false
        } catch (e: Exception) {
            log.error("检查 Token 黑名单失败：${e.message}")
            // 如果查询失败，默认不在黑名单（允许访问）
            false
        }
    }

    /**
     * 计算 Token 的 SHA256 哈希值
     *
     * @param token JWT Token
     * @return 哈希值（小写十六进制）
     */
    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(token.toByteArray(Charsets.UTF_8))

        // 转换为小写十六进制
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * 获取客户端 IP 地址
     */
    private fun getClientIp(): String = try {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        if (attributes != null) {
            val request = attributes.request

            // 尝试从 X-Forwarded-For 获取
            var ip = request.getHeader("X-Forwarded-For")
            if (ip.isNullOrEmpty() || "unknown".equals(ip, ignoreCase = true)) {
                ip = request.getHeader("X-Real-IP")
            }
            if (ip.isNullOrEmpty() || "unknown".equals(ip, ignoreCase = true)) {
                ip = request.remoteAddr
            }
            ip
        } else {
            "unknown"
        }
    } catch (e: Exception) {
        log.warn("获取客户端 IP 失败：${e.message}")
        "unknown"
    }
}
