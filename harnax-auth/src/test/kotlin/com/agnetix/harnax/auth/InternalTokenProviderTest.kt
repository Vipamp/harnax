package com.agnetix.harnax.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import java.util.*

class InternalTokenProviderTest {

    private val serviceId = "harnax-session-router"
    private val sharedSecret = "this-is-a-very-secure-shared-secret-key-for-testing"
    private val tokenTtlSeconds = 300L

    private lateinit var provider: InternalTokenProvider

    @BeforeEach
    fun setUp() {
        provider = InternalTokenProvider(serviceId, sharedSecret, tokenTtlSeconds)
    }

    @Nested
    inner class GenerateToken {
        @Test
        fun `generates a non-empty JWT token`() {
            val token = provider.generateToken()
            assertTrue(token.isNotBlank())
            assertTrue(token.count { it == '.' } == 2, "JWT should have 3 parts separated by dots")
        }

        @Test
        fun `token contains correct subject`() {
            val token = provider.generateToken()
            val claims = parseToken(token, sharedSecret)
            assertEquals(serviceId, claims.subject)
        }

        @Test
        fun `token is typed internal`() {
            // Without this claim a service token is indistinguishable from a browser's login
            // token, and the receiver would have to guess who may reach @InternalOnly.
            val claims = parseToken(provider.generateToken(), sharedSecret)
            assertEquals("internal", claims["typ"])
        }

        @Test
        fun `token has correct expiration`() {
            val before = System.currentTimeMillis()
            val token = provider.generateToken()
            val after = System.currentTimeMillis()

            val claims = parseToken(token, sharedSecret)
            // JWT NumericDate has second precision, milliseconds are truncated
            val beforeSec = before / 1000
            val afterSec = after / 1000
            val expSec = claims.expiration.time / 1000
            val ttlSec = tokenTtlSeconds
            assertTrue(
                expSec >= beforeSec + ttlSec && expSec <= afterSec + ttlSec,
                "Expiration (seconds) should be in ${beforeSec + ttlSec}..${afterSec + ttlSec}, got $expSec",
            )
        }

        @Test
        fun `token has unique JTI`() {
            val token1 = provider.generateToken()
            // Force cache miss by creating a new provider
            val provider2 = InternalTokenProvider(serviceId, sharedSecret, tokenTtlSeconds)
            val token2 = provider2.generateToken()

            val claims1 = parseToken(token1, sharedSecret)
            val claims2 = parseToken(token2, sharedSecret)
            assertNotEquals(claims1.id, claims2.id)
        }
    }

    @Nested
    inner class TokenCaching {
        @Test
        fun `consecutive calls return same cached token`() {
            val token1 = provider.generateToken()
            val token2 = provider.generateToken()
            assertEquals(token1, token2)
        }

        @Test
        fun `token refreshes when close to expiration`() {
            // Use a very short TTL so the token is within the refresh threshold
            val shortTtlProvider = InternalTokenProvider(serviceId, sharedSecret, 30L)
            val token1 = shortTtlProvider.generateToken()

            // Token expires in 30s, refresh threshold is 60s, so it should always refresh
            val token2 = shortTtlProvider.generateToken()
            assertNotEquals(token1, token2, "Token should be refreshed when within threshold")
        }
    }

    @Nested
    inner class VerifyToken {
        @Test
        fun `verifies a valid token and returns AuthContext`() {
            val token = provider.generateToken()
            val context = provider.verifyToken(token)

            assertEquals(serviceId, context.callerId)
            assertEquals(CallerType.INTERNAL_SERVICE, context.callerType)
            assertTrue(context.isInternal())
        }

        @Test
        fun `rejects token with wrong signing key`() {
            val token = provider.generateToken()
            val wrongProvider = InternalTokenProvider(
                serviceId,
                "a-completely-different-secret-key-that-is-long-enough",
                tokenTtlSeconds,
            )

            assertThrows<Exception> {
                wrongProvider.verifyToken(token)
            }
        }

        @Test
        fun `rejects expired token`() {
            // Create provider with 1 second TTL
            val expiredProvider = InternalTokenProvider(serviceId, sharedSecret, 1L)
            val token = expiredProvider.generateToken()

            Thread.sleep(1100)

            assertThrows<Exception> {
                expiredProvider.verifyToken(token)
            }
        }

        @Test
        fun `rejects tampered token`() {
            val token = provider.generateToken()
            val tamperedToken = token.dropLast(3) + "xxx"

            assertThrows<Exception> {
                provider.verifyToken(tamperedToken)
            }
        }

        @Test
        fun `rejects malformed token`() {
            assertThrows<Exception> {
                provider.verifyToken("not-a-jwt")
            }
        }

        @Test
        fun `rejects empty token`() {
            assertThrows<Exception> {
                provider.verifyToken("")
            }
        }

        @Test
        fun `cross-service verification with same secret`() {
            val providerA = InternalTokenProvider("service-a", sharedSecret, tokenTtlSeconds)
            val providerB = InternalTokenProvider("service-b", sharedSecret, tokenTtlSeconds)

            val token = providerA.generateToken()
            val context = providerB.verifyToken(token)

            assertEquals("service-a", context.callerId)
        }

        @Test
        fun `a user JWT signed with the same secret is not an internal service`() {
            // Deploy docs tell the operator to reuse one secret for admin and agent-service. If
            // that secret also lands in harnax.auth.internal.shared-secret, a browser token must
            // still not unlock @InternalOnly endpoints.
            val userToken = Jwts.builder()
                .subject("alice")
                .claim("userId", 7L)
                .claim("tenantId", 3L)
                .issuedAt(Date())
                .expiration(Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(sharedSecret.toByteArray(StandardCharsets.UTF_8)))
                .compact()

            val context = provider.verifyToken(userToken)

            assertEquals(CallerType.EXTERNAL_API, context.callerType)
            assertFalse(context.isInternal())
            assertEquals(7L, context.userId)
            assertEquals(3L, context.tenantId)
        }

        @Test
        fun `rejects a bearer that is neither a service token nor a user token`() {
            val unclassifiable = Jwts.builder()
                .subject("who-am-i")
                .issuedAt(Date())
                .expiration(Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(sharedSecret.toByteArray(StandardCharsets.UTF_8)))
                .compact()

            assertThrows<SecurityException> {
                provider.verifyToken(unclassifiable)
            }
        }
    }

    @Nested
    inner class AuthHeaders {
        @Test
        fun `returns Authorization and X-Caller-Id headers`() {
            val headers = provider.authHeaders()

            assertTrue(headers.containsKey("Authorization"))
            assertTrue(headers.containsKey("X-Caller-Id"))
        }

        @Test
        fun `Authorization header starts with Bearer`() {
            val headers = provider.authHeaders()
            assertTrue(headers["Authorization"]!!.startsWith("Bearer "))
        }

        @Test
        fun `X-Caller-Id header contains serviceId`() {
            val headers = provider.authHeaders()
            assertEquals(serviceId, headers["X-Caller-Id"])
        }

        @Test
        fun `Bearer token is verifiable`() {
            val headers = provider.authHeaders()
            val token = headers["Authorization"]!!.removePrefix("Bearer ")
            val context = provider.verifyToken(token)
            assertEquals(serviceId, context.callerId)
        }
    }

    private fun parseToken(token: String, secret: String) = Jwts.parser()
        .verifyWith(Keys.hmacShaKeyFor(secret.toByteArray(StandardCharsets.UTF_8)))
        .build()
        .parseSignedClaims(token)
        .payload
}
