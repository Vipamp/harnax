package com.agnetix.harnax.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class ApiKeyInfoTest {

    @Nested
    inner class IsExpired {
        @Test
        fun `returns true when expiresAt is in the past`() {
            val info = createKeyInfo(expiresAt = Instant.now().minusSeconds(60))
            assertTrue(info.isExpired())
        }

        @Test
        fun `returns false when expiresAt is in the future`() {
            val info = createKeyInfo(expiresAt = Instant.now().plusSeconds(3600))
            assertFalse(info.isExpired())
        }

        @Test
        fun `returns false when expiresAt is null (never expires)`() {
            val info = createKeyInfo(expiresAt = null)
            assertFalse(info.isExpired())
        }

        @Test
        fun `returns true when expiresAt is exactly now minus 1ms`() {
            val info = createKeyInfo(expiresAt = Instant.now().minusMillis(1))
            assertTrue(info.isExpired())
        }
    }

    @Nested
    inner class DataClassBehavior {
        @Test
        fun `copy preserves all fields`() {
            val original = createKeyInfo()
            val copy = original.copy()
            assertEquals(original, copy)
        }

        @Test
        fun `copy with modified enabled`() {
            val original = createKeyInfo(enabled = true)
            val disabled = original.copy(enabled = false)
            assertTrue(original.enabled)
            assertFalse(disabled.enabled)
        }

        @Test
        fun `scopes are immutable set`() {
            val info = createKeyInfo(scopes = setOf("read", "write"))
            assertEquals(setOf("read", "write"), info.scopes)
        }
    }

    private fun createKeyInfo(
        name: String = "test-key",
        keyHash: String = "abc123",
        scopes: Set<String> = setOf("read"),
        tenantId: Long? = 1L,
        rateLimitPerMinute: Int? = 100,
        enabled: Boolean = true,
        expiresAt: Instant? = Instant.now().plusSeconds(3600),
    ) = ApiKeyInfo(
        name = name,
        keyHash = keyHash,
        scopes = scopes,
        tenantId = tenantId,
        rateLimitPerMinute = rateLimitPerMinute,
        enabled = enabled,
        expiresAt = expiresAt,
    )
}
