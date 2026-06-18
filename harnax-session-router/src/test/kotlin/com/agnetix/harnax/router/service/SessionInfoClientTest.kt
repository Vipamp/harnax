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

class SessionInfoClientTest {

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
        data: SessionInfo?,
    ): ResponseEntity<ResultVo<SessionInfo?>> {
        return ResponseEntity.ok(ResultVo.success(data))
    }

    @Test
    fun `getSessionInfo returns data on valid response`() {
        withMockedRestTemplate { restTemplate ->
            val sessionInfo = SessionInfo(
                sessionId = "sess-1",
                agentId = 10L,
                agentName = "my-agent",
                modelId = 5L,
                modelName = "gpt-4",
                tenantId = 1L,
            )
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.GET),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<SessionInfo?>>>(),
                ),
            ).thenReturn(buildResponse(sessionInfo))

            val client = SessionInfoClient("http://admin:8080", tokenProvider())
            val result = client.getSessionInfo("sess-1")

            assertNotNull(result)
            assertEquals("sess-1", result!!.sessionId)
            assertEquals(10L, result.agentId)
            assertEquals("my-agent", result.agentName)
            assertEquals(5L, result.modelId)
            assertEquals("gpt-4", result.modelName)
            assertEquals(1L, result.tenantId)
        }
    }

    @Test
    fun `getSessionInfo returns null when admin returns null data`() {
        withMockedRestTemplate { restTemplate ->
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.GET),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<SessionInfo?>>>(),
                ),
            ).thenReturn(buildResponse(null))

            val client = SessionInfoClient("http://admin:8080", tokenProvider())
            val result = client.getSessionInfo("unknown-session")

            assertNull(result)
        }
    }

    @Test
    fun `getSessionInfo returns null on HTTP error`() {
        withMockedRestTemplate { restTemplate ->
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.GET),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<SessionInfo?>>>(),
                ),
            ).thenThrow(RestClientException("Connection timeout"))

            val client = SessionInfoClient("http://admin:8080", tokenProvider())
            val result = client.getSessionInfo("error-session")

            assertNull(result)
        }
    }

    @Test
    fun `cache returns same result without second HTTP call`() {
        withMockedRestTemplate { restTemplate ->
            val sessionInfo = SessionInfo(
                sessionId = "sess-cached",
                agentId = 1L,
                agentName = "agent",
                modelId = null,
                modelName = null,
                tenantId = null,
            )
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.GET),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<SessionInfo?>>>(),
                ),
            ).thenReturn(buildResponse(sessionInfo))

            val client = SessionInfoClient("http://admin:8080", tokenProvider())
            val result1 = client.getSessionInfo("sess-cached")
            val result2 = client.getSessionInfo("sess-cached")

            assertNotNull(result1)
            assertEquals(result1!!.sessionId, result2!!.sessionId)
            verify(restTemplate, times(1)).exchange(
                anyString(),
                eq(HttpMethod.GET),
                any<HttpEntity<*>>(),
                any<ParameterizedTypeReference<ResultVo<SessionInfo?>>>(),
            )
        }
    }

    @Test
    fun `different session IDs trigger separate HTTP calls`() {
        withMockedRestTemplate { restTemplate ->
            val info1 = SessionInfo(sessionId = "s1", agentId = 1L)
            val info2 = SessionInfo(sessionId = "s2", agentId = 2L)

            `when`(
                restTemplate.exchange(
                    contains("/sessions/s1/"),
                    eq(HttpMethod.GET),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<SessionInfo?>>>(),
                ),
            ).thenReturn(buildResponse(info1))

            `when`(
                restTemplate.exchange(
                    contains("/sessions/s2/"),
                    eq(HttpMethod.GET),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<SessionInfo?>>>(),
                ),
            ).thenReturn(buildResponse(info2))

            val client = SessionInfoClient("http://admin:8080", tokenProvider())
            val r1 = client.getSessionInfo("s1")
            val r2 = client.getSessionInfo("s2")

            assertEquals(1L, r1!!.agentId)
            assertEquals(2L, r2!!.agentId)
        }
    }

    @Test
    fun `session info with null optional fields`() {
        withMockedRestTemplate { restTemplate ->
            val sessionInfo = SessionInfo(
                sessionId = "sess-minimal",
                agentId = null,
                agentName = null,
                modelId = null,
                modelName = null,
                tenantId = null,
            )
            `when`(
                restTemplate.exchange(
                    anyString(),
                    eq(HttpMethod.GET),
                    any<HttpEntity<*>>(),
                    any<ParameterizedTypeReference<ResultVo<SessionInfo?>>>(),
                ),
            ).thenReturn(buildResponse(sessionInfo))

            val client = SessionInfoClient("http://admin:8080", tokenProvider())
            val result = client.getSessionInfo("sess-minimal")!!

            assertEquals("sess-minimal", result.sessionId)
            assertNull(result.agentId)
            assertNull(result.agentName)
            assertNull(result.modelId)
            assertNull(result.modelName)
            assertNull(result.tenantId)
        }
    }
}
