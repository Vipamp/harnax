package com.agnetix.harnax.admin.config

import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.ObjectMapper

/**
 * InternalApiAuthFilter 单元测试
 * 覆盖非 internal 路径放行、secret 正确/错误/缺失等场景
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InternalApiAuthFilter 内部API认证过滤器测试")
class InternalApiAuthFilterTest {

    companion object {
        private const val CORRECT_SECRET = "internal-shared-secret-abc123"
        private const val INTERNAL_URI = "/api/admin/internal/agents/1"
    }

    private val objectMapper = ObjectMapper()

    private fun createFilter(secret: String = CORRECT_SECRET): InternalApiAuthFilter = InternalApiAuthFilter(objectMapper, secret)

    private fun buildRequest(uri: String, authHeader: String? = null): MockHttpServletRequest {
        val request = MockHttpServletRequest("GET", uri)
        request.requestURI = uri
        if (authHeader != null) {
            request.addHeader("Authorization", authHeader)
        }
        return request
    }

    @Nested
    @DisplayName("非 internal 路径放行测试")
    inner class NonInternalPathTests {

        @Test
        @DisplayName("非internal路径 - 无需认证直接放行")
        fun `doFilter should pass through non-internal path without auth`() {
            val filter = createFilter()
            val request = buildRequest("/api/admin/agents")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            assertNotNull(chain.request, "过滤器链应继续执行")
            assertEquals(HttpServletResponse.SC_OK, response.status)
        }

        @Test
        @DisplayName("非internal路径 - 即使secret未配置也放行")
        fun `doFilter should pass through non-internal path even when secret not configured`() {
            val filter = createFilter(secret = "")
            val request = buildRequest("/api/admin/models")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            assertNotNull(chain.request)
            assertEquals(HttpServletResponse.SC_OK, response.status)
        }
    }

    @Nested
    @DisplayName("Secret 正确测试")
    inner class CorrectSecretTests {

        @Test
        @DisplayName("secret正确 - 放行请求")
        fun `doFilter should pass through internal path with correct secret`() {
            val filter = createFilter()
            val request = buildRequest(INTERNAL_URI, authHeader = "Bearer $CORRECT_SECRET")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            assertNotNull(chain.request, "过滤器链应继续执行")
            assertEquals(HttpServletResponse.SC_OK, response.status)
        }

        @Test
        @DisplayName("secret正确但带空格 - trim后仍放行")
        fun `doFilter should trim token before comparing`() {
            val filter = createFilter()
            val request = buildRequest(INTERNAL_URI, authHeader = "Bearer $CORRECT_SECRET ")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            assertNotNull(chain.request)
            assertEquals(HttpServletResponse.SC_OK, response.status)
        }
    }

    @Nested
    @DisplayName("Secret 错误测试")
    inner class WrongSecretTests {

        @Test
        @DisplayName("secret错误 - 返回401")
        fun `doFilter should return 401 for wrong secret`() {
            val filter = createFilter()
            val request = buildRequest(INTERNAL_URI, authHeader = "Bearer wrong-secret")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            assertNull(chain.request, "过滤器链不应继续执行")
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status)
            assertTrue(response.contentType!!.contains("application/json"))
            assertTrue(response.contentAsString.contains("Invalid credentials"))
        }

        @Test
        @DisplayName("误传JWT token - 返回401且包含JWT提示")
        fun `doFilter should return 401 with jwt hint when jwt token sent`() {
            val filter = createFilter()
            // 三段式 JWT 格式的错误 token
            val request = buildRequest(INTERNAL_URI, authHeader = "Bearer aaa.bbb.ccc")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status)
            assertTrue(response.contentAsString.contains("JWT"), "响应应包含误传JWT的提示信息")
        }
    }

    @Nested
    @DisplayName("Secret 缺失测试")
    inner class MissingSecretTests {

        @Test
        @DisplayName("无Authorization头 - 返回401")
        fun `doFilter should return 401 when authorization header missing`() {
            val filter = createFilter()
            val request = buildRequest(INTERNAL_URI)
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            assertNull(chain.request)
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status)
            assertTrue(response.contentAsString.contains("Missing or malformed Authorization header"))
        }

        @Test
        @DisplayName("非Bearer前缀 - 返回401")
        fun `doFilter should return 401 for non-bearer authorization header`() {
            val filter = createFilter()
            val request = buildRequest(INTERNAL_URI, authHeader = "Basic dXNlcjpwYXNz")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            assertNull(chain.request)
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status)
        }

        @Test
        @DisplayName("服务端secret未配置 - 返回401拒绝所有internal请求")
        fun `doFilter should return 401 when server secret not configured`() {
            val filter = createFilter(secret = "")
            val request = buildRequest(INTERNAL_URI, authHeader = "Bearer anything")
            val response = MockHttpServletResponse()
            val chain = MockFilterChain()

            filter.doFilter(request, response, chain)

            assertNull(chain.request)
            assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status)
            assertTrue(response.contentAsString.contains("not configured"))
        }
    }
}
