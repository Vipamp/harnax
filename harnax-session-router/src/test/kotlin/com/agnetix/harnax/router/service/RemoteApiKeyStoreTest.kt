package com.agnetix.harnax.router.service

import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.common.dto.ResultVo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate

class RemoteApiKeyStoreTest {

    private fun tokenProvider(): InternalTokenProvider {
        val mock = mock(InternalTokenProvider::class.java)
        `when`(mock.authHeaders(anyString())).thenReturn(
            mapOf("Authorization" to "Bearer test-token", "X-Caller-Id" to "router-0"),
        )
        return mock
    }

    private fun withMockedRestTemplate(block: (RestTemplate) -> Unit) {
        mockConstruction(RestTemplate::class.java).use { mocked ->
            block(mocked.constructed().first())
        }
    }

    private fun buildResponse(
        data: RemoteApiKeyStore.ApiKeyValidateResponse?,
    ): ResponseEntity<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>> {
        return ResponseEntity.ok(ResultVo.success(data))
    }

    @Test
    fun `findByKeyHash returns ApiKeyInfo on valid response`() {
        withMockedRestTemplate { restTemplate ->
            val responseData = RemoteApiKeyStore.ApiKeyValidateResponse(
                name = "test-key",
                keyHash = "abc123",
                scopes = "router:invoke,api:chat",
                tenantId = 1L,
                rateLimit = 100,
                enabled = true,
                expiresAt = null,
            )
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.POST),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>>>(),
                ),
            ).thenReturn(buildResponse(responseData))

            val store = RemoteApiKeyStore("http://admin:8080", tokenProvider())
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
        withMockedRestTemplate { restTemplate ->
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.POST),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>>>(),
                ),
            ).thenReturn(buildResponse(null))

            val store = RemoteApiKeyStore("http://admin:8080", tokenProvider())
            val result = store.findByKeyHash("unknown-hash")

            assertNull(result)
        }
    }

    @Test
    fun `findByKeyHash returns null on HTTP error`() {
        withMockedRestTemplate { restTemplate ->
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.POST),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>>>(),
                ),
            ).thenThrow(RestClientException("Connection refused"))

            val store = RemoteApiKeyStore("http://admin:8080", tokenProvider())
            val result = store.findByKeyHash("error-hash")

            assertNull(result)
        }
    }

    @Test
    fun `scopes are correctly parsed from comma-separated string`() {
        withMockedRestTemplate { restTemplate ->
            val responseData = RemoteApiKeyStore.ApiKeyValidateResponse(
                name = "scoped-key",
                keyHash = "hash1",
                scopes = "router:invoke, api:chat , api:session",
                tenantId = null,
                rateLimit = 60,
                enabled = true,
            )
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.POST),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>>>(),
                ),
            ).thenReturn(buildResponse(responseData))

            val store = RemoteApiKeyStore("http://admin:8080", tokenProvider())
            val result = store.findByKeyHash("hash1")!!

            assertEquals(3, result.scopes.size)
            assertTrue(result.scopes.contains("router:invoke"))
            assertTrue(result.scopes.contains("api:chat"))
            assertTrue(result.scopes.contains("api:session"))
        }
    }

    @Test
    fun `expiresAt is parsed from ISO string`() {
        withMockedRestTemplate { restTemplate ->
            val responseData = RemoteApiKeyStore.ApiKeyValidateResponse(
                name = "expiring-key",
                keyHash = "hash2",
                scopes = "api:chat",
                tenantId = null,
                rateLimit = 60,
                enabled = true,
                expiresAt = "2026-12-31T23:59:59Z",
            )
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.POST),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>>>(),
                ),
            ).thenReturn(buildResponse(responseData))

            val store = RemoteApiKeyStore("http://admin:8080", tokenProvider())
            val result = store.findByKeyHash("hash2")!!

            assertNotNull(result.expiresAt)
        }
    }

    @Test
    fun `cache returns same result without second HTTP call`() {
        withMockedRestTemplate { restTemplate ->
            val responseData = RemoteApiKeyStore.ApiKeyValidateResponse(
                name = "cached-key",
                keyHash = "hash-cached",
                scopes = "api:chat",
                tenantId = 1L,
                rateLimit = 60,
                enabled = true,
            )
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.POST),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>>>(),
                ),
            ).thenReturn(buildResponse(responseData))

            val store = RemoteApiKeyStore("http://admin:8080", tokenProvider())
            val result1 = store.findByKeyHash("hash-cached")
            val result2 = store.findByKeyHash("hash-cached")

            assertNotNull(result1)
            assertEquals(result1!!.name, result2!!.name)
            verify(restTemplate, times(1)).exchange(
                anyString(),
                eq(HttpMethod.POST),
                any<HttpEntity<*>>(),
                any<ParameterizedTypeReference<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>>>(),
            )
        }
    }

    @Test
    fun `disabled key is returned with enabled=false`() {
        withMockedRestTemplate { restTemplate ->
            val responseData = RemoteApiKeyStore.ApiKeyValidateResponse(
                name = "disabled-key",
                keyHash = "hash-dis",
                scopes = "api:chat",
                tenantId = null,
                rateLimit = 60,
                enabled = false,
            )
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.POST),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>>>(),
                ),
            ).thenReturn(buildResponse(responseData))

            val store = RemoteApiKeyStore("http://admin:8080", tokenProvider())
            val result = store.findByKeyHash("hash-dis")!!

            assertFalse(result.enabled)
        }
    }
}
