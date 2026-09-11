package com.agnetix.harnax.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.*
import javax.crypto.SecretKey

/**
 * Provides JWT generation and verification for internal service-to-service authentication.
 * Tokens no longer carry scope information — only caller identity is encoded, and that identity
 * is stamped with a `typ=internal` claim so [verifyToken] can tell a peer service apart from a
 * user's own login token signed with the same key.
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
                .claim(CLAIM_TYPE, TOKEN_TYPE_INTERNAL)
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

    /**
     * Verify a bearer token and classify its caller.
     *
     * The signature alone cannot tell a peer service from a browser: [AuthProperties] keeps this
     * secret separate from `jwt.secret`, but an operator who points both at the same value — the
     * default in every deploy doc — would otherwise hand every logged-in user `@InternalOnly`
     * access. So the type claim decides, and a token with no type is only trusted as far as its
     * own identity claims go.
     */
    fun verifyToken(token: String): AuthContext {
        val claims = Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .payload

        val userId = (claims[CLAIM_USER_ID] as? Number)?.toLong()
        if (claims[CLAIM_TYPE] == TOKEN_TYPE_INTERNAL) {
            return AuthContext(
                callerId = claims.subject,
                // A service token may still name a human when one is riding on behalf of a user.
                userId = userId,
                tenantId = (claims[CLAIM_TENANT_ID] as? Number)?.toLong(),
                callerType = CallerType.INTERNAL_SERVICE,
            )
        }

        if (userId == null) {
            throw SecurityException(
                "Token carries neither the '$TOKEN_TYPE_INTERNAL' type claim nor a userId: " +
                    "refusing to treat an unclassifiable bearer as an internal service",
            )
        }

        return AuthContext(
            callerId = claims.subject ?: "unknown",
            userId = userId,
            tenantId = (claims[CLAIM_TENANT_ID] as? Number)?.toLong(),
            callerType = CallerType.EXTERNAL_API,
        )
    }

    fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${generateToken()}",
        "X-Caller-Id" to serviceId,
    )

    companion object {
        private const val REFRESH_THRESHOLD_MS = 60_000L

        private const val CLAIM_TYPE = "typ"
        private const val CLAIM_USER_ID = "userId"
        private const val CLAIM_TENANT_ID = "tenantId"

        /** Marks a token as service-to-service. Absent means "not a service", see [verifyToken]. */
        private const val TOKEN_TYPE_INTERNAL = "internal"
    }
}
