package com.agnetix.harnax.router.service

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

    private fun withMockedRestTemplate(block: (RestTemplate, RemoteApiKeyStore) -> Unit) {
        mockConstruction(RestTemplate::class.java).use { mocked ->
            val store = RemoteApiKeyStore("http://admin:8080", "test-secret")
            block(mocked.constructed().first(), store)
        }
    }

    private fun buildResponse(
        data: RemoteApiKeyStore.ApiKeyValidateResponse?,
    ): ResponseEntity<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>> = ResponseEntity.ok(ResultVo.success(data))

    @Test
    fun `findByKeyHash returns ApiKeyInfo on valid response`() {
        withMockedRestTemplate { restTemplate, store ->
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
        withMockedRestTemplate { restTemplate, store ->
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.POST),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>>>(),
                ),
            ).thenReturn(buildResponse(null))

            val result = store.findByKeyHash("unknown-hash")

            assertNull(result)
        }
    }

    @Test
    fun `findByKeyHash returns null on HTTP error`() {
        withMockedRestTemplate { restTemplate, store ->
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.POST),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>>>(),
                ),
            ).thenThrow(RestClientException("Connection refused"))

            val result = store.findByKeyHash("error-hash")

            assertNull(result)
        }
    }

    @Test
    fun `scopes are correctly parsed from comma-separated string`() {
        withMockedRestTemplate { restTemplate, store ->
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

            val result = store.findByKeyHash("hash1")!!

            assertEquals(3, result.scopes.size)
            assertTrue(result.scopes.contains("router:invoke"))
            assertTrue(result.scopes.contains("api:chat"))
            assertTrue(result.scopes.contains("api:session"))
        }
    }

    @Test
    fun `expiresAt is parsed from ISO string`() {
        withMockedRestTemplate { restTemplate, store ->
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

            val result = store.findByKeyHash("hash2")!!

            assertNotNull(result.expiresAt)
        }
    }

    @Test
    fun `cache returns same result without second HTTP call`() {
        withMockedRestTemplate { restTemplate, store ->
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
    fun `findByKeyHash does NOT cache null result — second call hits HTTP again`() {
        withMockedRestTemplate { restTemplate, store ->
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.POST),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>>>(),
                ),
            ).thenReturn(buildResponse(null))

            val result1 = store.findByKeyHash("not-found-hash")
            val result2 = store.findByKeyHash("not-found-hash")

            assertNull(result1)
            assertNull(result2)
            verify(restTemplate, times(2)).exchange(
                anyString(),
                eq(HttpMethod.POST),
                any<HttpEntity<*>>(),
                any<ParameterizedTypeReference<ResultVo<RemoteApiKeyStore.ApiKeyValidateResponse?>>>(),
            )
        }
    }

    @Test
    fun `disabled key is returned with enabled=false`() {
        withMockedRestTemplate { restTemplate, store ->
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

            val result = store.findByKeyHash("hash-dis")!!

            assertFalse(result.enabled)
        }
    }
}
