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
 * Token blacklist service implementation
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
            // Calculate SHA256 hash of Token (avoid storing complete Token)
            val tokenHash = hashToken(token)

            // Get client IP
            val clientIp = getClientIp()

            // Build blacklist record
            val blacklist = SysTokenBlacklist.builder()
                .token(token) // Store complete Token (optional, for auditing)
                .tokenHash(tokenHash)
                .username(username)
                .userId(userId)
                .reason(reason)
                .expireTime(expireTime)
                .createTime(LocalDateTime.now())
                .createIp(clientIp)
                .build()

            // Save to database
            tokenBlacklistMapper.insert(blacklist)

            log.info("Token added to blacklist, userId: {}, username: {}, reason: {}", userId, username, reason)
        } catch (e: Exception) {
            log.error("Failed to add Token to blacklist: ${e.message}", e)
            // Do not throw exception to avoid affecting logout process
        }
    }

    override fun isBlacklisted(token: String): Boolean {
        return try {
            // Calculate SHA256 hash of Token
            val tokenHash = hashToken(token)

            // Query if in blacklist and not expired
            val blacklist = tokenBlacklistMapper.selectByTokenHash(tokenHash)

            if (blacklist != null) {
                log.debug("Token is in blacklist, userId: {}", blacklist.userId)
                return true
            }

            false
        } catch (e: Exception) {
            log.error("Failed to check Token blacklist: ${e.message}")
            // If query fails, default to not in blacklist (allow access)
            false
        }
    }

    /**
     * Calculate SHA256 hash of Token
     *
     * @param token JWT Token
     * @return Hash value (lowercase hexadecimal)
     */
    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(token.toByteArray(Charsets.UTF_8))

        // Convert to lowercase hexadecimal
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Get client IP address
     */
    private fun getClientIp(): String = try {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        if (attributes != null) {
            val request = attributes.request

            // Try to get from X-Forwarded-For
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
        log.warn("Failed to get client IP: ${e.message}")
        "unknown"
    }
}
