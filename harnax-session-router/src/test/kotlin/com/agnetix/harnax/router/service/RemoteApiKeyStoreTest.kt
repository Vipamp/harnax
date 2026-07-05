package com.agnetix.harnax.router.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class RemoteApiKeyStoreTest {

    private fun withMockedAdminClient(block: (AdminClientService, RemoteApiKeyStore) -> Unit) {
        val adminClient = mock(AdminClientService::class.java)
        val store = RemoteApiKeyStore(adminClient)
        block(adminClient, store)
    }

    @Test
    fun `findByKeyHash returns ApiKeyInfo on valid response`() {
        withMockedAdminClient { adminClient, store ->
            val responseData = AdminClientService.ApiKeyValidateResponse(
                name = "test-key",
                keyHash = "abc123",
                scopes = "router:invoke,api:chat",
                tenantId = 1L,
                rateLimit = 100,
                enabled = true,
                expiresAt = null,
            )
            `when`(runBlocking { adminClient.validateApiKey("abc123") }).thenReturn(responseData)

            val result = store.findByKeyHash("abc123")

            assertNotNull(result)
            assertEquals("test-key", result!!.name)
            assertEquals("abc123", result.keyHash)
            assertTrue(result.scopes.contains("router:invoke"))
            assertTrue(result.scopes.contains("api:chat"))
            assertEquals(1L, result.tenantId)
            assertEquals(100, result.rateLimitPerMinute)
            assertTrue(result.enabled)
            assertNull(result.expiresAt)
        }
    }

    @Test
    fun `findByKeyHash returns null when admin returns null data`() {
        withMockedAdminClient { adminClient, store ->
            `when`(runBlocking { adminClient.validateApiKey("unknown-hash") }).thenReturn(null)

            val result = store.findByKeyHash("unknown-hash")

            assertNull(result)
        }
    }

    @Test
    fun `findByKeyHash returns null on exception`() {
        withMockedAdminClient { adminClient, store ->
            `when`(runBlocking { adminClient.validateApiKey("error-hash") }).thenThrow(RuntimeException("Connection refused"))

            val result = store.findByKeyHash("error-hash")

            assertNull(result)
        }
    }

    @Test
    fun `scopes are correctly parsed from comma-separated string`() {
        withMockedAdminClient { adminClient, store ->
            val responseData = AdminClientService.ApiKeyValidateResponse(
                name = "scoped-key",
                keyHash = "hash1",
                scopes = "router:invoke, api:chat , api:session",
                tenantId = null,
                rateLimit = 60,
                enabled = true,
            )
            `when`(runBlocking { adminClient.validateApiKey("hash1") }).thenReturn(responseData)

            val result = store.findByKeyHash("hash1")!!

            assertEquals(3, result.scopes.size)
            assertTrue(result.scopes.contains("router:invoke"))
            assertTrue(result.scopes.contains("api:chat"))
            assertTrue(result.scopes.contains("api:session"))
        }
    }

    @Test
    fun `expiresAt is parsed from ISO string`() {
        withMockedAdminClient { adminClient, store ->
            val responseData = AdminClientService.ApiKeyValidateResponse(
                name = "expiring-key",
                keyHash = "hash2",
                scopes = "api:chat",
                tenantId = null,
                rateLimit = 60,
                enabled = true,
                expiresAt = "2026-12-31T23:59:59Z",
            )
            `when`(runBlocking { adminClient.validateApiKey("hash2") }).thenReturn(responseData)

            val result = store.findByKeyHash("hash2")!!

            assertNotNull(result.expiresAt)
        }
    }

    @Test
    fun `cache returns same result without second HTTP call`() {
        withMockedAdminClient { adminClient, store ->
            val responseData = AdminClientService.ApiKeyValidateResponse(
                name = "cached-key",
                keyHash = "hash-cached",
                scopes = "api:chat",
                tenantId = 1L,
                rateLimit = 60,
                enabled = true,
            )
            `when`(runBlocking { adminClient.validateApiKey("hash-cached") }).thenReturn(responseData)

            val result1 = store.findByKeyHash("hash-cached")
            val result2 = store.findByKeyHash("hash-cached")

            assertNotNull(result1)
            assertEquals(result1!!.name, result2!!.name)
            runBlocking { verify(adminClient, times(1)).validateApiKey("hash-cached") }
        }
    }

    @Test
    fun `findByKeyHash caches null result as negative cache to prevent stampede`() {
        withMockedAdminClient { adminClient, store ->
            `when`(runBlocking { adminClient.validateApiKey("not-found-hash") }).thenReturn(null)

            val result1 = store.findByKeyHash("not-found-hash")
            val result2 = store.findByKeyHash("not-found-hash")

            assertNull(result1)
            assertNull(result2)
            // Negative result is cached (Optional.empty), so admin is called only once
            runBlocking { verify(adminClient, times(1)).validateApiKey("not-found-hash") }
        }
    }

    @Test
    fun `disabled key is returned with enabled=false`() {
        withMockedAdminClient { adminClient, store ->
            val responseData = AdminClientService.ApiKeyValidateResponse(
                name = "disabled-key",
                keyHash = "hash-dis",
                scopes = "api:chat",
                tenantId = null,
                rateLimit = 60,
                enabled = false,
            )
            `when`(runBlocking { adminClient.validateApiKey("hash-dis") }).thenReturn(responseData)

            val result = store.findByKeyHash("hash-dis")!!

            assertFalse(result.enabled)
        }
    }
}
