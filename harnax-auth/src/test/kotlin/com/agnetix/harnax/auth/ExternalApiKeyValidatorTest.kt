package com.agnetix.harnax.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.*
import java.security.MessageDigest
import java.time.Instant

class ExternalApiKeyValidatorTest {

    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var validator: ExternalApiKeyValidator

    @BeforeEach
    fun setUp() {
        apiKeyStore = mock(ApiKeyStore::class.java)
        validator = ExternalApiKeyValidator(apiKeyStore)
    }

    @Nested
    inner class ValidKey {
        @Test
        fun `returns AuthContext for valid enabled key`() {
            val rawKey = "my-api-key-123"
            val keyHash = sha256(rawKey)
            val keyInfo = ApiKeyInfo(
                name = "test-app",
                keyHash = keyHash,
                scopes = setOf("read", "write"),
                tenantId = 42L,
                rateLimitPerMinute = 100,
                enabled = true,
                expiresAt = Instant.now().plusSeconds(3600),
            )
            `when`(apiKeyStore.findByKeyHash(keyHash)).thenReturn(keyInfo)

            val context = validator.validate(rawKey)

            assertEquals("test-app", context.callerId)
            assertEquals(CallerType.EXTERNAL_API, context.callerType)
            assertEquals(42L, context.tenantId)
            assertEquals(100, context.rateLimitPerMinute)
            assertEquals(setOf("read", "write"), context.scopes)
            assertFalse(context.isInternal())
        }

        @Test
        fun `handles key with null expiration`() {
            val rawKey = "never-expires-key"
            val keyHash = sha256(rawKey)
            val keyInfo = ApiKeyInfo(
                name = "permanent-app",
                keyHash = keyHash,
                scopes = setOf("read"),
                tenantId = null,
                rateLimitPerMinute = null,
                enabled = true,
                expiresAt = null,
            )
            `when`(apiKeyStore.findByKeyHash(keyHash)).thenReturn(keyInfo)

            val context = validator.validate(rawKey)

            assertEquals("permanent-app", context.callerId)
            assertNull(context.tenantId)
            assertNull(context.rateLimitPerMinute)
        }
    }

    @Nested
    inner class InvalidKey {
        @Test
        fun `throws SecurityException for unknown key`() {
            val rawKey = "unknown-key"
            val keyHash = sha256(rawKey)
            `when`(apiKeyStore.findByKeyHash(keyHash)).thenReturn(null)

            val ex = assertThrows<SecurityException> {
                validator.validate(rawKey)
            }
            assertTrue(ex.message!!.contains("Invalid API key"))
        }

        @Test
        fun `throws SecurityException for disabled key`() {
            val rawKey = "disabled-key"
            val keyHash = sha256(rawKey)
            val keyInfo = ApiKeyInfo(
                name = "disabled-app",
                keyHash = keyHash,
                scopes = setOf("read"),
                tenantId = null,
                rateLimitPerMinute = null,
                enabled = false,
                expiresAt = null,
            )
            `when`(apiKeyStore.findByKeyHash(keyHash)).thenReturn(keyInfo)

            val ex = assertThrows<SecurityException> {
                validator.validate(rawKey)
            }
            assertTrue(ex.message!!.contains("disabled"))
        }

        @Test
        fun `throws SecurityException for expired key`() {
            val rawKey = "expired-key"
            val keyHash = sha256(rawKey)
            val keyInfo = ApiKeyInfo(
                name = "expired-app",
                keyHash = keyHash,
                scopes = setOf("read"),
                tenantId = null,
                rateLimitPerMinute = null,
                enabled = true,
                expiresAt = Instant.now().minusSeconds(60),
            )
            `when`(apiKeyStore.findByKeyHash(keyHash)).thenReturn(keyInfo)

            val ex = assertThrows<SecurityException> {
                validator.validate(rawKey)
            }
            assertTrue(ex.message!!.contains("expired"))
        }

        @Test
        fun `disabled check takes precedence over expired`() {
            val rawKey = "disabled-and-expired-key"
            val keyHash = sha256(rawKey)
            val keyInfo = ApiKeyInfo(
                name = "disabled-expired-app",
                keyHash = keyHash,
                scopes = setOf("read"),
                tenantId = null,
                rateLimitPerMinute = null,
                enabled = false,
                expiresAt = Instant.now().minusSeconds(60),
            )
            `when`(apiKeyStore.findByKeyHash(keyHash)).thenReturn(keyInfo)

            val ex = assertThrows<SecurityException> {
                validator.validate(rawKey)
            }
            assertTrue(ex.message!!.contains("disabled"), "Should fail on 'disabled' before checking 'expired'")
        }
    }

    @Nested
    inner class Sha256Consistency {
        @Test
        fun `same key always produces same hash`() {
            val rawKey = "consistent-key"
            val hash1 = sha256(rawKey)
            val hash2 = sha256(rawKey)
            assertEquals(hash1, hash2)
        }

        @Test
        fun `different keys produce different hashes`() {
            val hash1 = sha256("key-a")
            val hash2 = sha256("key-b")
            assertNotEquals(hash1, hash2)
        }

        @Test
        fun `hash is 64 hex characters`() {
            val hash = sha256("test-key")
            assertEquals(64, hash.length)
            assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
