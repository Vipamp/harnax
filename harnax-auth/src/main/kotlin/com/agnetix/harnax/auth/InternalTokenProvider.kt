package com.agnetix.harnax.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.*
import javax.crypto.SecretKey

/**
 * Provides JWT generation and verification for internal service-to-service authentication.
 * Tokens no longer carry scope information — only caller identity is encoded.
 */
class InternalTokenProvider(
    private val serviceId: String,
    sharedSecret: String,
    private val tokenTtlSeconds: Long,
) {

    private val log = LoggerFactory.getLogger(InternalTokenProvider::class.java)
    private val signingKey: SecretKey = Keys.hmacShaKeyFor(sharedSecret.toByteArray(StandardCharsets.UTF_8))

    @Volatile private var cachedToken: String? = null

    @Volatile private var cachedExpiresAt: Long = 0L

    fun generateToken(): String {
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (cached != null && (cachedExpiresAt - now) > REFRESH_THRESHOLD_MS) {
            return cached
        }

        // Double-check locking: only one thread regenerates the token
        synchronized(this) {
            val recheck = cachedToken
            if (recheck != null && (cachedExpiresAt - now) > REFRESH_THRESHOLD_MS) {
                return recheck
            }

            val expiresAt = now + tokenTtlSeconds * 1000
            val token = Jwts.builder()
                .subject(serviceId)
                .issuedAt(Date(now))
                .expiration(Date(expiresAt))
                .id(UUID.randomUUID().toString())
                .signWith(signingKey)
                .compact()

            cachedToken = token
            cachedExpiresAt = expiresAt
            return token
        }
    }

    fun verifyToken(token: String): AuthContext {
        val claims = Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .payload

        return AuthContext(
            callerId = claims.subject,
            callerType = CallerType.INTERNAL_SERVICE,
        )
    }

    fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${generateToken()}",
        "X-Caller-Id" to serviceId,
    )

    companion object {
        private const val REFRESH_THRESHOLD_MS = 60_000L
    }
}
