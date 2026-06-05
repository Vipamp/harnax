package com.agnetix.harnax.agent.service.util

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
 * JWT utility
 */
@Component
class JwtUtil {

    private val log = LoggerFactory.getLogger(JwtUtil::class.java)

    @Value($$"${jwt.secret}")
    private var secret: String = "harnax-secret-key-2026"

    @Value($$"${jwt.expiration}")
    private var expiration: Long = 7200000

    /**
     * Get signing key
     */
    private fun getSigningKey(): SecretKey {
        val keyBytes = secret.toByteArray(StandardCharsets.UTF_8)
        return Keys.hmacShaKeyFor(keyBytes)
    }

    /**
     * Generate JWT Token
     *
     * @param userId User ID
     * @param username Username
     * @param tenantId Tenant ID
     * @param isAdmin Is admin flag
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
     * Get user ID from token
     *
     * @param token JWT Token
     * @return User ID
     */
    fun getUserIdFromToken(token: String): Long {
        val claims = getClaimsFromToken(token)
        return claims["userId", Integer::class.java].toLong()
    }

    /**
     * Get username from token
     *
     * @param token JWT Token
     * @return Username
     */
    fun getUsernameFromToken(token: String): String {
        val claims = getClaimsFromToken(token)
        return claims.subject
    }

    /**
     * Validate if token is valid
     *
     * @param token JWT Token
     * @return true/false
     */
    fun validateToken(token: String): Boolean = try {
        val expiration = getExpirationDateFromToken(token)
        !expiration.before(Date())
    } catch (e: Exception) {
        log.error("Failed to validate token: {}", e.message)
        false
    }

    /**
     * Get expiration time
     *
     * @param token JWT Token
     * @return Expiration time
     */
    private fun getExpirationDateFromToken(token: String): Date {
        val claims = getClaimsFromToken(token)
        return claims.expiration
    }

    /**
     * Get claims
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
     * Get tenant ID from token
     *
     * @param token JWT Token
     * @return Tenant ID
     */
    fun getTenantIdFromToken(token: String): Long? = try {
        val claims = getClaimsFromToken(token)
        claims["tenantId", Long::class.java]
    } catch (e: Exception) {
        null
    }

    /**
     * Get isAdmin flag from token
     *
     * @param token JWT Token
     * @return isAdmin (1=admin, 0=regular user)
     */
    fun getIsAdminFromToken(token: String): Int? = try {
        val claims = getClaimsFromToken(token)
        claims["isAdmin", Int::class.java]
    } catch (e: Exception) {
        null
    }

    /**
     * Get expiration time (milliseconds)
     *
     * @return Expiration time
     */
    fun getExpirationTime(): Long = expiration
}
