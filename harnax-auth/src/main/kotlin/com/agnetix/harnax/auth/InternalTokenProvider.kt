package com.agnetix.harnax.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

class InternalTokenProvider(
    private val serviceId: String,
    sharedSecret: String,
    private val tokenTtlSeconds: Long,
) {

    private val log = LoggerFactory.getLogger(InternalTokenProvider::class.java)
    private val signingKey: SecretKey = Keys.hmacShaKeyFor(sharedSecret.toByteArray(StandardCharsets.UTF_8))

    private data class CachedToken(val token: String, val expiresAt: Long)

    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    fun generateToken(scope: String): String {
        val now = System.currentTimeMillis()
        val cached = tokenCache[scope]
        if (cached != null && (cached.expiresAt - now) > REFRESH_THRESHOLD_MS) {
            return cached.token
        }

        val expiresAt = now + tokenTtlSeconds * 1000
        val token = Jwts.builder()
            .subject(serviceId)
            .claim("scp", scope)
            .issuedAt(Date(now))
            .expiration(Date(expiresAt))
            .id(UUID.randomUUID().toString())
            .signWith(signingKey)
            .compact()

        tokenCache[scope] = CachedToken(token, expiresAt)
        return token
    }

    fun verifyToken(token: String): AuthContext {
        val claims = Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .payload

        return AuthContext(
            callerId = claims.subject,
            scope = claims["scp", String::class.java],
        )
    }

    fun authHeaders(scope: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${generateToken(scope)}",
        "X-Caller-Id" to serviceId,
    )

    companion object {
        private const val REFRESH_THRESHOLD_MS = 60_000L
    }
}
