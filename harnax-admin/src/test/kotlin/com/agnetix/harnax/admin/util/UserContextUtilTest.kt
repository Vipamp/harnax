package com.agnetix.harnax.admin.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.anyOrNull
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * UserContextUtil 单元测试
 * 使用 MockHttpServletRequest + RequestContextHolder 模拟请求上下文
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserContextUtil 用户上下文工具测试")
class UserContextUtilTest {

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    private fun setRequestWithHeader(headerValue: String?) {
        val request = MockHttpServletRequest()
        if (headerValue != null) {
            request.addHeader("Authorization", headerValue)
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    @Nested
    @DisplayName("获取当前请求测试")
    inner class GetCurrentRequestTests {

        @Test
        @DisplayName("getCurrentRequest - 存在请求上下文时返回请求")
        fun `getCurrentRequest should return request when context exists`() {
            setRequestWithHeader("Bearer some-token")

            val request = UserContextUtil.getCurrentRequest()

            assertNotNull(request)
            assertEquals("Bearer some-token", request?.getHeader("Authorization"))
        }

        @Test
        @DisplayName("getCurrentRequest - 无请求上下文时返回null")
        fun `getCurrentRequest should return null when no context`() {
            RequestContextHolder.resetRequestAttributes()

            assertNull(UserContextUtil.getCurrentRequest())
        }
    }

    @Nested
    @DisplayName("获取 Token 测试")
    inner class GetTokenTests {

        @Test
        @DisplayName("getToken - 正常Bearer头返回token")
        fun `getToken should return token from bearer header`() {
            setRequestWithHeader("Bearer my-jwt-token")

            assertEquals("my-jwt-token", UserContextUtil.getToken())
        }

        @Test
        @DisplayName("getToken - 无Authorization头返回null")
        fun `getToken should return null when header missing`() {
            setRequestWithHeader(null)

            assertNull(UserContextUtil.getToken())
        }

        @Test
        @DisplayName("getToken - 非Bearer前缀返回null")
        fun `getToken should return null when header has no bearer prefix`() {
            setRequestWithHeader("Basic dXNlcjpwYXNz")

            assertNull(UserContextUtil.getToken())
        }

        @Test
        @DisplayName("getToken - 无请求上下文返回null")
        fun `getToken should return null when no request context`() {
            RequestContextHolder.resetRequestAttributes()

            assertNull(UserContextUtil.getToken())
        }

        @Test
        @DisplayName("getToken - Bearer后为空字符串时返回空串")
        fun `getToken should return empty string when bearer token is empty`() {
            setRequestWithHeader("Bearer ")

            assertEquals("", UserContextUtil.getToken())
        }
    }

    @Nested
    @DisplayName("获取当前用户名测试")
    inner class GetCurrentUsernameTests {

        @Test
        @DisplayName("getCurrentUsername - 有效token返回用户名")
        fun `getCurrentUsername should return username for valid token`() {
            setRequestWithHeader("Bearer valid-token")
            `when`(jwtUtil.validateToken("valid-token")).thenReturn(true)
            `when`(jwtUtil.getUsernameFromToken("valid-token")).thenReturn("admin")

            val username = UserContextUtil.getCurrentUsername(jwtUtil)

            assertEquals("admin", username)
        }

        @Test
        @DisplayName("getCurrentUsername - 无token抛出未登录异常")
        fun `getCurrentUsername should throw when token missing`() {
            setRequestWithHeader(null)

            val exception = assertThrows<RuntimeException> {
                UserContextUtil.getCurrentUsername(jwtUtil)
            }
            assertEquals("Not logged in", exception.message)
        }

        @Test
        @DisplayName("getCurrentUsername - 无效token抛出未登录异常")
        fun `getCurrentUsername should throw when token invalid`() {
            setRequestWithHeader("Bearer invalid-token")
            `when`(jwtUtil.validateToken("invalid-token")).thenReturn(false)

            val exception = assertThrows<RuntimeException> {
                UserContextUtil.getCurrentUsername(jwtUtil)
            }
            assertEquals("Not logged in", exception.message)
        }

        @Test
        @DisplayName("getCurrentUsername - 解析token异常时抛出未登录异常")
        fun `getCurrentUsername should throw when parsing fails`() {
            setRequestWithHeader("Bearer broken-token")
            `when`(jwtUtil.validateToken("broken-token")).thenReturn(true)
            `when`(jwtUtil.getUsernameFromToken("broken-token")).thenThrow(RuntimeException("parse error"))

            val exception = assertThrows<RuntimeException> {
                UserContextUtil.getCurrentUsername(jwtUtil)
            }
            assertEquals("Not logged in", exception.message)
        }

        @Test
        @DisplayName("getCurrentUsername - 无请求上下文抛出未登录异常")
        fun `getCurrentUsername should throw when no request context`() {
            RequestContextHolder.resetRequestAttributes()

            assertThrows<RuntimeException> {
                UserContextUtil.getCurrentUsername(jwtUtil)
            }
        }
    }

    @Nested
    @DisplayName("获取当前用户ID测试")
    inner class GetCurrentUserIdTests {

        @Test
        @DisplayName("getCurrentUserId - 有效token返回用户ID")
        fun `getCurrentUserId should return userId for valid token`() {
            setRequestWithHeader("Bearer valid-token")
            `when`(jwtUtil.validateToken("valid-token")).thenReturn(true)
            `when`(jwtUtil.getUserIdFromToken("valid-token")).thenReturn(42L)

            assertEquals(42L, UserContextUtil.getCurrentUserId(jwtUtil))
        }

        @Test
        @DisplayName("getCurrentUserId - 无token返回null")
        fun `getCurrentUserId should return null when token missing`() {
            setRequestWithHeader(null)

            assertNull(UserContextUtil.getCurrentUserId(jwtUtil))
        }

        @Test
        @DisplayName("getCurrentUserId - 无效token返回null")
        fun `getCurrentUserId should return null when token invalid`() {
            setRequestWithHeader("Bearer invalid-token")
            `when`(jwtUtil.validateToken("invalid-token")).thenReturn(false)

            assertNull(UserContextUtil.getCurrentUserId(jwtUtil))
        }

        @Test
        @DisplayName("getCurrentUserId - 解析异常返回null而不抛异常")
        fun `getCurrentUserId should return null when parsing fails`() {
            setRequestWithHeader("Bearer broken-token")
            `when`(jwtUtil.validateToken("broken-token")).thenReturn(true)
            `when`(jwtUtil.getUserIdFromToken("broken-token")).thenThrow(RuntimeException("parse error"))

            assertNull(UserContextUtil.getCurrentUserId(jwtUtil))
        }

        @Test
        @DisplayName("getCurrentUserId - 无请求上下文返回null")
        fun `getCurrentUserId should return null when no request context`() {
            RequestContextHolder.resetRequestAttributes()
            `when`(jwtUtil.validateToken(anyOrNull())).thenReturn(true)

            assertNull(UserContextUtil.getCurrentUserId(jwtUtil))
        }
    }
}
