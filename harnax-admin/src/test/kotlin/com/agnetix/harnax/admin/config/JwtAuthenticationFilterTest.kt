package com.agnetix.harnax.admin.config

import com.agnetix.harnax.admin.service.SysTokenBlacklistService
import com.agnetix.harnax.admin.util.JwtUtil
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.AuthenticationServiceException
import org.springframework.security.core.context.SecurityContextHolder

/**
 * JwtAuthenticationFilter 单元测试
 * 使用 MockHttpServletRequest/MockHttpServletResponse/MockFilterChain 模拟过滤器链
 * 覆盖白名单路径放行、无token、无效token、黑名单token、有效token等场景
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JwtAuthenticationFilter JWT 认证过滤器测试")
class JwtAuthenticationFilterTest {

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var tokenBlacklistService: SysTokenBlacklistService

    private lateinit var filter: JwtAuthenticationFilter

    @BeforeEach
    fun setUp() {
        filter = JwtAuthenticationFilter(jwtUtil, tokenBlacklistService)
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    private fun buildRequest(uri: String, token: String? = null): MockHttpServletRequest {
        val request = MockHttpServletRequest("GET", uri)
        request.requestURI = uri
        if (token != null) {
            request.addHeader("Authorization", "Bearer $token")
        }
        return request
    }

    @Nested
    @DisplayName("白名单路径测试")
    inner class WhitelistPathTests {

        @Test
        @DisplayName("白名单路径 - 内部API路径跳过JWT校验（黑名单token也放行）")
        fun `doFilter should skip jwt validation for internal api path`() {
            val request = buildRequest("/api/admin/internal/agents/1", token = "blacklisted-token")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            `when`(tokenBlacklistService.isBlacklisted("blacklisted-token")).thenReturn(true)

            // 白名单路径应跳过过滤逻辑，不抛出黑名单异常
            filter.doFilter(request, response, chain)

            assertNotNull(chain.request, "过滤器链应继续执行")
            assertNull(SecurityContextHolder.getContext().authentication)
        }

        @Test
        @DisplayName("白名单路径 - 登录相关路径跳过JWT校验")
        fun `doFilter should skip jwt validation for public auth paths`() {
            val whitelistedPaths = listOf(
                "/api/admin/auth/login",
                "/api/admin/auth/logout",
                "/api/admin/auth/captcha",
                "/api/admin/auth/login-methods",
                "/api/admin/mp/auth/login",
            )

            `when`(tokenBlacklistService.isBlacklisted("blacklisted-token")).thenReturn(true)

            for (path in whitelistedPaths) {
                val request = buildRequest(path, token = "blacklisted-token")
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()

                // 白名单路径不应触发黑名单校验异常
                filter.doFilter(request, response, chain)
                assertNotNull(chain.request, "路径 $path 应直接放行")
            }
        }

        @Test
        @DisplayName("非白名单业务路径 - 黑名单token被拒绝")
        fun `doFilter should reject blacklisted token on business path`() {
            val request = buildRequest("/api/admin/agents", token = "blacklisted-token")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            `when`(tokenBlacklistService.isBlacklisted("blacklisted-token")).thenReturn(true)

            assertThrows<AuthenticationServiceException> {
                filter.doFilter(request, response, chain)
            }
        }

        @Test
        @DisplayName("白名单路径无token也能放行")
        fun `doFilter should pass through whitelisted path without token`() {
            val request = buildRequest("/api/admin/auth/login")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            // 过滤器链继续执行且未设置认证信息
            assertNotNull(chain.request)
            assertNull(SecurityContextHolder.getContext().authentication)
        }
    }

    @Nested
    @DisplayName("无 Token 请求测试")
    inner class NoTokenTests {

        @Test
        @DisplayName("无token请求 - 放行但不设置认证信息")
        fun `doFilter should continue chain without authentication when no token`() {
            val request = buildRequest("/api/admin/agents")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            assertNotNull(chain.request, "过滤器链应继续执行")
            assertNull(SecurityContextHolder.getContext().authentication, "不应设置认证信息")
        }

        @Test
        @DisplayName("非Bearer前缀的Authorization头 - 视为无token放行")
        fun `doFilter should treat non-bearer header as no token`() {
            val request = buildRequest("/api/admin/agents")
            request.addHeader("Authorization", "Basic dXNlcjpwYXNz")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            assertNotNull(chain.request)
            assertNull(SecurityContextHolder.getContext().authentication)
        }
    }

    @Nested
    @DisplayName("无效 Token 测试")
    inner class InvalidTokenTests {

        @Test
        @DisplayName("无效token - 抛出AuthenticationServiceException")
        fun `doFilter should throw exception for invalid token`() {
            val request = buildRequest("/api/admin/agents", token = "invalid-token")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            `when`(tokenBlacklistService.isBlacklisted("invalid-token")).thenReturn(false)
            `when`(jwtUtil.validateToken("invalid-token")).thenReturn(false)

            val exception = assertThrows<AuthenticationServiceException> {
                filter.doFilter(request, response, chain)
            }
            assertTrue(exception.message?.contains("invalid or expired") == true)
            assertNull(SecurityContextHolder.getContext().authentication)
        }

        @Test
        @DisplayName("token解析异常 - 包装为AuthenticationServiceException")
        fun `doFilter should wrap unexpected exception as authentication exception`() {
            val request = buildRequest("/api/admin/agents", token = "broken-token")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            `when`(tokenBlacklistService.isBlacklisted("broken-token")).thenReturn(false)
            `when`(jwtUtil.validateToken("broken-token")).thenReturn(true)
            `when`(jwtUtil.getUserIdFromToken("broken-token")).thenThrow(RuntimeException("parse error"))

            val exception = assertThrows<AuthenticationServiceException> {
                filter.doFilter(request, response, chain)
            }
            assertTrue(exception.message?.contains("Authentication failed") == true)
        }
    }

    @Nested
    @DisplayName("黑名单 Token 测试")
    inner class BlacklistedTokenTests {

        @Test
        @DisplayName("黑名单token - 抛出AuthenticationServiceException拒绝访问")
        fun `doFilter should throw exception for blacklisted token`() {
            val request = buildRequest("/api/admin/agents", token = "blacklisted-token")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            `when`(tokenBlacklistService.isBlacklisted("blacklisted-token")).thenReturn(true)

            val exception = assertThrows<AuthenticationServiceException> {
                filter.doFilter(request, response, chain)
            }
            assertTrue(exception.message?.contains("expired") == true)
            assertNull(SecurityContextHolder.getContext().authentication)
        }
    }

    @Nested
    @DisplayName("有效 Token 测试")
    inner class ValidTokenTests {

        @Test
        @DisplayName("有效token - 设置SecurityContext并继续过滤器链")
        fun `doFilter should set security context for valid token`() {
            val request = buildRequest("/api/admin/agents", token = "valid-token")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            `when`(tokenBlacklistService.isBlacklisted("valid-token")).thenReturn(false)
            `when`(jwtUtil.validateToken("valid-token")).thenReturn(true)
            `when`(jwtUtil.getUserIdFromToken("valid-token")).thenReturn(1L)
            `when`(jwtUtil.getUsernameFromToken("valid-token")).thenReturn("admin")

            filter.doFilter(request, response, chain)

            val authentication = SecurityContextHolder.getContext().authentication
            assertNotNull(authentication, "应设置认证信息")
            assertEquals("admin", authentication.principal)
            assertNotNull(chain.request, "过滤器链应继续执行")
        }

        @Test
        @DisplayName("有效token - 认证详情包含请求信息")
        fun `doFilter should set authentication details from request`() {
            val request = buildRequest("/api/admin/agents", token = "valid-token")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            `when`(tokenBlacklistService.isBlacklisted("valid-token")).thenReturn(false)
            `when`(jwtUtil.validateToken("valid-token")).thenReturn(true)
            `when`(jwtUtil.getUserIdFromToken("valid-token")).thenReturn(1L)
            `when`(jwtUtil.getUsernameFromToken("valid-token")).thenReturn("admin")

            filter.doFilter(request, response, chain)

            val authentication = SecurityContextHolder.getContext().authentication
            assertNotNull(authentication.details)
        }
    }
}
