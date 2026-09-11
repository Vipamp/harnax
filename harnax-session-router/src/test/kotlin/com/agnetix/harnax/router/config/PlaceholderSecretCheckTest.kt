package com.agnetix.harnax.router.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PlaceholderSecretCheckTest {

    private fun check(
        cacheType: String,
        sharedSecret: String = PLACEHOLDER,
        adminApiSecret: String = PLACEHOLDER,
        authEnabled: Boolean = true,
    ) = PlaceholderSecretCheck(cacheType, authEnabled, sharedSecret, adminApiSecret)

    @Test
    fun `a cluster node with the repository secret does not start`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            check("redis").verify()
        }

        assert(failure.message!!.contains("HARNAX_AUTH_SECRET"))
        assert(failure.message!!.contains("ADMIN_INTERNAL_API_SECRET"))
    }

    @Test
    fun `a cluster node with real secrets starts`() {
        assertDoesNotThrow {
            check(
                "redis",
                sharedSecret = "a-real-shared-secret-of-at-least-32-chars",
                adminApiSecret = "a-real-admin-secret-of-at-least-32-chars-long",
            ).verify()
        }
    }

    @Test
    fun `a single local node is not blocked over its secret`() {
        assertDoesNotThrow { check("local").verify() }
    }

    @Test
    fun `a node that reports one placeholder secret names only that one`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            check("redis", sharedSecret = "a-real-shared-secret-of-at-least-32-chars").verify()
        }

        assert(!failure.message!!.contains("HARNAX_AUTH_SECRET"))
        assert(failure.message!!.contains("ADMIN_INTERNAL_API_SECRET"))
    }

    @Test
    fun `authentication turned off is not this check's problem`() {
        assertDoesNotThrow { check("redis", authEnabled = false).verify() }
    }

    private companion object {
        const val PLACEHOLDER = "change-me-in-production-min-32-chars!!"
    }
}
