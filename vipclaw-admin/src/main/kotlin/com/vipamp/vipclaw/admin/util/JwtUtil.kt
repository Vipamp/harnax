package com.vipamp.vipclaw.admin.util

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.*
import javax.crypto.SecretKey

/**
 * JWT 工具类
 */
@Component
class JwtUtil {

    private val log = LoggerFactory.getLogger(JwtUtil::class.java)

    @Value($$"${jwt.secret}")
    private var secret: String = "vipclaw-secret-key-2026"

    @Value($$"${jwt.expiration}")
    private var expiration: Long = 7200000

    /**
     * 获取签名密钥
     */
    private fun getSigningKey(): SecretKey {
        val keyBytes = secret.toByteArray(StandardCharsets.UTF_8)
        return Keys.hmacShaKeyFor(keyBytes)
    }

    /**
     * 生成 JWT Token
     *
     * @param userId 用户 ID
     * @param username 用户名
     * @param tenantId 租户 ID
     * @param isAdmin 是否管理员
     * @return JWT Token
     */
    fun generateToken(userId: Long, username: String, tenantId: Long? = null, isAdmin: Int = 0): String {
        val claims: MutableMap<String, Any> = HashMap()
        claims["userId"] = userId
        claims["username"] = username
        if (tenantId != null) {
            claims["tenantId"] = tenantId
        }
        claims["isAdmin"] = isAdmin

        val now = Date()
        val expirationDate = Date(now.time + expiration)

        return Jwts.builder()
            .claims(claims)
            .subject(username)
            .issuedAt(now)
            .expiration(expirationDate)
            .signWith(getSigningKey())
            .compact()
    }

    /**
     * 从 Token 中获取用户 ID
     *
     * @param token JWT Token
     * @return 用户 ID
     */
    fun getUserIdFromToken(token: String): Long {
        val claims = getClaimsFromToken(token)
        return claims["userId", Integer::class.java].toLong()
    }

    /**
     * 从 Token 中获取用户名
     *
     * @param token JWT Token
     * @return 用户名
     */
    fun getUsernameFromToken(token: String): String {
        val claims = getClaimsFromToken(token)
        return claims.subject
    }

    /**
     * 验证 Token 是否有效
     *
     * @param token JWT Token
     * @return true/false
     */
    fun validateToken(token: String): Boolean = try {
        val expiration = getExpirationDateFromToken(token)
        !expiration.before(Date())
    } catch (e: Exception) {
        log.error("验证 Token 失败：{}", e.message)
        false
    }

    /**
     * 获取过期时间
     *
     * @param token JWT Token
     * @return 过期时间
     */
    private fun getExpirationDateFromToken(token: String): Date {
        val claims = getClaimsFromToken(token)
        return claims.expiration
    }

    /**
     * 获取 Claims
     *
     * @param token JWT Token
     * @return Claims
     */
    private fun getClaimsFromToken(token: String): Claims = Jwts.parser()
        .verifyWith(getSigningKey())
        .build()
        .parseSignedClaims(token)
        .payload

    /**
     * 从 Token 中获取租户 ID
     *
     * @param token JWT Token
     * @return 租户 ID
     */
    fun getTenantIdFromToken(token: String): Long? = try {
        val claims = getClaimsFromToken(token)
        claims["tenantId", Long::class.java]
    } catch (e: Exception) {
        null
    }

    /**
     * 从 Token 中获取 isAdmin 标识
     *
     * @param token JWT Token
     * @return isAdmin (1=管理员, 0=普通用户)
     */
    fun getIsAdminFromToken(token: String): Int? = try {
        val claims = getClaimsFromToken(token)
        claims["isAdmin", Int::class.java]
    } catch (e: Exception) {
        null
    }

    /**
     * 获取过期时间（毫秒）
     *
     * @return 过期时间
     */
    fun getExpirationTime(): Long = expiration
}
