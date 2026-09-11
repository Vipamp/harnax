package com.agnetix.harnax.admin.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * McpOAuthStateStore Unit Tests
 *
 * The store holds the half of an authorization that must not outlive its use, so the cases are the
 * ones about lifetime: one read, an expiry that is honoured even while the entry is still in the map,
 * a cap that refuses rather than grows, and a per-user count for the cap one caller is allowed to take.
 */
class McpOAuthStateStoreTest {

    private val store = McpOAuthStateStore()

    private fun pending(
        userId: Long = 7L,
        expiresAtNanos: Long = McpOAuthStateStore.newExpiresAtNanos(),
    ): PendingAuthorization = PendingAuthorization(
        tenantId = 1L,
        userId = userId,
        mcpId = 3L,
        issuer = "https://as.example.com",
        codeVerifier = "verifier",
        redirectUri = "http://localhost:8000/mcp/oauth/callback",
        resource = "http://mcp.example.com:3000/mcp",
        requestedScopes = listOf("mcp:read"),
        expiresAtNanos = expiresAtNanos,
    )

    @Test
    @DisplayName("consume 是一次性的:第二次读同一个 state 拿不到东西")
    fun `a state can be consumed once`() {
        store.put("abc", pending())

        assertNotNull(store.consume("abc"))
        assertNull(store.consume("abc"))
        assertEquals(0, store.size())
    }

    @Test
    @DisplayName("空白的 state 不算合法查询")
    fun `a blank state matches nothing`() {
        store.put(" ", pending())

        assertNull(store.consume(""))
        assertNull(store.consume(" "))
    }

    @Test
    @DisplayName("过期的条目读不出来,并且从表里消失")
    fun `an expired entry cannot be read back`() {
        store.put("stale", pending(expiresAtNanos = System.nanoTime() - 1))

        assertNull(store.consume("stale"))
    }

    @Test
    @DisplayName("put 顺手清掉已过期的,过期条目不占额度")
    fun `a sweep on put frees the expired entries`() {
        repeat(McpOAuthStateStore.MAX_PENDING) {
            store.put("stale-$it", pending(expiresAtNanos = System.nanoTime() - 1))
        }

        assertTrue(store.put("fresh", pending()))
        assertEquals(1, store.size())
    }

    @Test
    @DisplayName("在途数量到上限时拒绝而不是无界增长")
    fun `the store refuses beyond its cap`() {
        repeat(McpOAuthStateStore.MAX_PENDING) { store.put("s$it", pending()) }

        assertFalse(store.put("one-too-many", pending()))
        assertEquals(McpOAuthStateStore.MAX_PENDING, store.size())
    }

    @Test
    @DisplayName("未过期就能读到,内容按放进去的算")
    fun `a live entry carries the identity the exchange has to compare against`() {
        store.put("live", pending())

        val taken = store.consume("live")!!
        assertEquals(1L, taken.tenantId)
        assertEquals(7L, taken.userId)
        assertEquals("verifier", taken.codeVerifier)
        assertFalse(taken.expired)
    }

    @Test
    @DisplayName("countFor 只数这个用户的,并且先清掉已过期的")
    fun `a per-user count covers one user and drops the expired first`() {
        store.put("mine", pending())
        store.put("theirs", pending(userId = 8L))
        store.put("stale", pending(expiresAtNanos = System.nanoTime() - 1))

        assertEquals(1, store.countFor(7L))
        assertEquals(1, store.countFor(8L))
        assertEquals(0, store.countFor(9L), "a user with nothing in flight is not capped")
        assertEquals(2, store.size(), "the sweep the count triggered already dropped the expired entry")
    }
}
