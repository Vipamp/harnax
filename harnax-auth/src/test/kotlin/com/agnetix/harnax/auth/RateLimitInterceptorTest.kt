package com.agnetix.harnax.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.web.method.HandlerMethod
import tools.jackson.databind.ObjectMapper
import java.io.PrintWriter
import java.io.StringWriter

class RateLimitInterceptorTest {

    private lateinit var rateLimitChecker: RateLimitChecker
    private lateinit var interceptor: RateLimitInterceptor
    private lateinit var request: HttpServletRequest
    private lateinit var response: HttpServletResponse
    private lateinit var handler: HandlerMethod
    private lateinit var stringWriter: StringWriter

    @BeforeEach
    fun setUp() {
        rateLimitChecker = mock(RateLimitChecker::class.java)
        val objectMapper = ObjectMapper()
        interceptor = RateLimitInterceptor(rateLimitChecker, objectMapper)
        request = mock(HttpServletRequest::class.java)
        response = mock(HttpServletResponse::class.java)
        handler = mock(HandlerMethod::class.java)
        `when`(request.requestURI).thenReturn("/api/agent/chat")
    }

    @AfterEach
    fun tearDown() {
        AuthContextHolder.clear()
    }

    @Nested
    inner class NonHandlerMethod {
        @Test
        fun `passes through for non-HandlerMethod`() {
            val result = interceptor.preHandle(request, response, "not-a-handler")
            assertTrue(result)
        }
    }

    @Nested
    inner class NoAuthContext {
        @Test
        fun `passes through when no AuthContext`() {
            val result = interceptor.preHandle(request, response, handler)
            assertTrue(result)
        }
    }

    @Nested
    inner class InternalCaller {
        @Test
        fun `skips rate limit for internal callers`() {
            AuthContextHolder.set(
                AuthContext(callerId = "svc-a", callerType = CallerType.INTERNAL_SERVICE),
            )

            val result = interceptor.preHandle(request, response, handler)

            assertTrue(result)
            verifyNoInteractions(rateLimitChecker)
        }
    }

    @Nested
    inner class ExternalCaller {
        @Test
        fun `passes through when no rate limit configured`() {
            AuthContextHolder.set(
                AuthContext(
                    callerId = "ext-app",
                    callerType = CallerType.EXTERNAL_API,
                    rateLimitPerMinute = null,
                ),
            )

            val result = interceptor.preHandle(request, response, handler)

            assertTrue(result)
            verifyNoInteractions(rateLimitChecker)
        }

        @Test
        fun `passes through when rate limit not exceeded`() {
            AuthContextHolder.set(
                AuthContext(
                    callerId = "ext-app",
                    callerType = CallerType.EXTERNAL_API,
                    rateLimitPerMinute = 100,
                ),
            )
            `when`(rateLimitChecker.tryAcquire("ext-app", 100)).thenReturn(true)

            val result = interceptor.preHandle(request, response, handler)

            assertTrue(result)
            verify(rateLimitChecker).tryAcquire("ext-app", 100)
        }

        @Test
        fun `returns 429 when rate limit exceeded`() {
            AuthContextHolder.set(
                AuthContext(
                    callerId = "ext-app",
                    callerType = CallerType.EXTERNAL_API,
                    rateLimitPerMinute = 60,
                ),
            )
            `when`(rateLimitChecker.tryAcquire("ext-app", 60)).thenReturn(false)

            stringWriter = StringWriter()
            val writer = PrintWriter(stringWriter)
            `when`(response.writer).thenReturn(writer)

            val result = interceptor.preHandle(request, response, handler)

            assertFalse(result)
            verify(response).status = 429
            verify(response).setHeader("Retry-After", "60")
            verify(response).contentType = "application/json"

            writer.flush()
            val responseBody = stringWriter.toString()
            assertTrue(responseBody.contains("429"))
            assertTrue(responseBody.contains("Rate limit exceeded"))
        }

        @Test
        fun `uses correct caller ID for rate limit check`() {
            AuthContextHolder.set(
                AuthContext(
                    callerId = "specific-app",
                    callerType = CallerType.EXTERNAL_API,
                    rateLimitPerMinute = 50,
                ),
            )
            `when`(rateLimitChecker.tryAcquire("specific-app", 50)).thenReturn(true)

            interceptor.preHandle(request, response, handler)

            verify(rateLimitChecker).tryAcquire("specific-app", 50)
        }
    }
}
