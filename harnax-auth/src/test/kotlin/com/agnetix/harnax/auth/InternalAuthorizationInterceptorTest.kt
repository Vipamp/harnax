package com.agnetix.harnax.auth

import jakarta.servlet.DispatcherType
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
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

class InternalAuthorizationInterceptorTest {

    private lateinit var interceptor: InternalAuthorizationInterceptor
    private lateinit var request: HttpServletRequest
    private lateinit var response: HttpServletResponse
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper()
        interceptor = InternalAuthorizationInterceptor(objectMapper)
        request = mock(HttpServletRequest::class.java)
        response = mock(HttpServletResponse::class.java)
        `when`(request.dispatcherType).thenReturn(DispatcherType.REQUEST)
    }

    @AfterEach
    fun tearDown() {
        AuthContextHolder.clear()
    }

    @InternalOnly
    fun internalOnlyMethod() {}

    fun publicMethod() {}

    @InternalOnly
    class InternalOnlyClass {
        fun someMethod() {}
    }

    private fun handlerFor(method: Method): HandlerMethod {
        val handler = mock(HandlerMethod::class.java)
        `when`(handler.method).thenReturn(method)
        `when`(handler.beanType).thenReturn(InternalAuthorizationInterceptorTest::class.java)
        return handler
    }

    @Nested
    inner class NonHandlerMethod {
        @Test
        fun `passes through for non-HandlerMethod handler`() {
            val result = interceptor.preHandle(request, response, "not-a-handler")
            assertTrue(result)
        }
    }

    @Nested
    inner class NoAnnotation {
        @Test
        fun `passes through when no InternalOnly annotation`() {
            val handler = handlerFor(InternalAuthorizationInterceptorTest::class.java.getMethod("publicMethod"))
            `when`(request.requestURI).thenReturn("/api/public")

            val result = interceptor.preHandle(request, response, handler)
            assertTrue(result)
        }
    }

    @Nested
    inner class WithAnnotation {
        @Test
        fun `passes through for internal caller`() {
            val handler = handlerFor(InternalAuthorizationInterceptorTest::class.java.getMethod("internalOnlyMethod"))
            AuthContextHolder.set(
                AuthContext(callerId = "svc-a", callerType = CallerType.INTERNAL_SERVICE),
            )
            `when`(request.requestURI).thenReturn("/api/internal")

            val result = interceptor.preHandle(request, response, handler)
            assertTrue(result)
        }

        @Test
        fun `rejects external caller with 403`() {
            val handler = handlerFor(InternalAuthorizationInterceptorTest::class.java.getMethod("internalOnlyMethod"))
            AuthContextHolder.set(
                AuthContext(callerId = "ext-app", callerType = CallerType.EXTERNAL_API),
            )
            `when`(request.requestURI).thenReturn("/api/internal")
            `when`(response.isCommitted).thenReturn(false)

            val baos = ByteArrayOutputStream()
            val outputStream = mock(jakarta.servlet.ServletOutputStream::class.java)
            `when`(response.outputStream).thenReturn(outputStream)
            doAnswer { invocation ->
                val buf = invocation.getArgument<ByteArray>(0)
                val off = invocation.getArgument<Int>(1)
                val len = invocation.getArgument<Int>(2)
                baos.write(buf, off, len)
                null
            }.`when`(outputStream).write(any(), anyInt(), anyInt())
            doAnswer { invocation ->
                val buf = invocation.getArgument<ByteArray>(0)
                baos.write(buf)
                null
            }.`when`(outputStream).write(any<ByteArray>())

            val result = interceptor.preHandle(request, response, handler)

            assertFalse(result)
            verify(response).status = HttpServletResponse.SC_FORBIDDEN
            val body = baos.toString()
            assertTrue(body.contains("403"), "Response body should contain status code 403")
            assertTrue(body.contains("internal services"), "Response body should mention internal services restriction")
        }

        @Test
        fun `rejects when AuthContext is missing with 401`() {
            val handler = handlerFor(InternalAuthorizationInterceptorTest::class.java.getMethod("internalOnlyMethod"))
            // No AuthContext set
            `when`(request.requestURI).thenReturn("/api/internal")
            `when`(request.remoteAddr).thenReturn("10.0.0.1")
            `when`(response.isCommitted).thenReturn(false)

            val baos = ByteArrayOutputStream()
            val outputStream = mock(jakarta.servlet.ServletOutputStream::class.java)
            `when`(response.outputStream).thenReturn(outputStream)
            doAnswer { invocation ->
                val buf = invocation.getArgument<ByteArray>(0)
                baos.write(buf)
                null
            }.`when`(outputStream).write(any<ByteArray>())

            val result = interceptor.preHandle(request, response, handler)

            assertFalse(result)
            verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
            val body = baos.toString()
            assertTrue(body.contains("401"), "Response body should contain status code 401")
            assertTrue(body.contains("Authentication required"), "Response body should mention authentication required")
        }
    }

    @Nested
    inner class ClassLevelAnnotation {
        @Test
        fun `rejects external caller when class has InternalOnly annotation`() {
            val method = InternalOnlyClass::class.java.getMethod("someMethod")
            val handler = mock(HandlerMethod::class.java)
            `when`(handler.method).thenReturn(method)
            `when`(handler.beanType).thenReturn(InternalOnlyClass::class.java)

            AuthContextHolder.set(
                AuthContext(callerId = "ext-app", callerType = CallerType.EXTERNAL_API),
            )
            `when`(request.requestURI).thenReturn("/api/internal")
            `when`(response.isCommitted).thenReturn(false)

            val outputStream = mock(jakarta.servlet.ServletOutputStream::class.java)
            `when`(response.outputStream).thenReturn(outputStream)

            val result = interceptor.preHandle(request, response, handler)

            assertFalse(result, "Should reject external caller for class-level @InternalOnly")
            verify(response).status = HttpServletResponse.SC_FORBIDDEN
        }
    }

    @Nested
    inner class AsyncDispatch {
        @Test
        fun `skips check for async dispatch`() {
            `when`(request.dispatcherType).thenReturn(DispatcherType.ASYNC)
            val handler = handlerFor(InternalAuthorizationInterceptorTest::class.java.getMethod("internalOnlyMethod"))

            val result = interceptor.preHandle(request, response, handler)
            assertTrue(result, "Should pass through for async dispatch")
        }
    }

    @Nested
    inner class ResponseCommitted {
        @Test
        fun `does not write error when response already committed`() {
            val handler = handlerFor(InternalAuthorizationInterceptorTest::class.java.getMethod("internalOnlyMethod"))
            AuthContextHolder.set(
                AuthContext(callerId = "ext-app", callerType = CallerType.EXTERNAL_API),
            )
            `when`(request.requestURI).thenReturn("/api/internal")
            `when`(response.isCommitted).thenReturn(true)

            val result = interceptor.preHandle(request, response, handler)

            assertFalse(result)
            verify(response, never()).status = anyInt()
        }
    }
}
