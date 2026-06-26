package com.agnetix.harnax.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpResponse

class AuthRestTemplateInterceptorTest {

    private val sharedSecret = "this-is-a-very-secure-shared-secret-key-for-testing"
    private lateinit var tokenProvider: InternalTokenProvider
    private lateinit var interceptor: AuthRestTemplateInterceptor

    @BeforeEach
    fun setUp() {
        tokenProvider = InternalTokenProvider("test-service", sharedSecret, 300)
        interceptor = AuthRestTemplateInterceptor(tokenProvider)
    }

    @Nested
    inner class HeaderInjection {
        @Test
        fun `adds Authorization header`() {
            val request = createMockRequest()
            val execution = mock(ClientHttpRequestExecution::class.java)
            val mockResponse = mock(ClientHttpResponse::class.java)
            `when`(execution.execute(any(), any<ByteArray>())).thenReturn(mockResponse)

            interceptor.intercept(request, ByteArray(0), execution)

            assertNotNull(request.headers.getFirst("Authorization"))
            assertTrue(request.headers.getFirst("Authorization")!!.startsWith("Bearer "))
        }

        @Test
        fun `adds X-Caller-Id header`() {
            val request = createMockRequest()
            val execution = mock(ClientHttpRequestExecution::class.java)
            val mockResponse = mock(ClientHttpResponse::class.java)
            `when`(execution.execute(any(), any<ByteArray>())).thenReturn(mockResponse)

            interceptor.intercept(request, ByteArray(0), execution)

            assertEquals("test-service", request.headers.getFirst("X-Caller-Id"))
        }

        @Test
        fun `Bearer token is verifiable`() {
            val request = createMockRequest()
            val execution = mock(ClientHttpRequestExecution::class.java)
            val mockResponse = mock(ClientHttpResponse::class.java)
            `when`(execution.execute(any(), any<ByteArray>())).thenReturn(mockResponse)

            interceptor.intercept(request, ByteArray(0), execution)

            val token = request.headers.getFirst("Authorization")!!.removePrefix("Bearer ")
            val context = tokenProvider.verifyToken(token)
            assertEquals("test-service", context.callerId)
        }

        @Test
        fun `passes request body through`() {
            val request = createMockRequest()
            val execution = mock(ClientHttpRequestExecution::class.java)
            val mockResponse = mock(ClientHttpResponse::class.java)
            val body = """{"key":"value"}""".toByteArray()
            `when`(execution.execute(any(), any<ByteArray>())).thenReturn(mockResponse)

            interceptor.intercept(request, body, execution)

            verify(execution).execute(eq(request), eq(body))
        }

        @Test
        fun `returns execution response`() {
            val request = createMockRequest()
            val execution = mock(ClientHttpRequestExecution::class.java)
            val mockResponse = mock(ClientHttpResponse::class.java)
            `when`(execution.execute(any(), any<ByteArray>())).thenReturn(mockResponse)

            val result = interceptor.intercept(request, ByteArray(0), execution)

            assertSame(mockResponse, result)
        }
    }

    private fun createMockRequest(): HttpRequest {
        val request = mock(HttpRequest::class.java)
        val headers = HttpHeaders()
        `when`(request.headers).thenReturn(headers)
        return request
    }
}
