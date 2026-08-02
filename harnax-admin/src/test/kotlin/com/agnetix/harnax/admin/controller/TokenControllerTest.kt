package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.UserTenantService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.SysUserMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * TokenController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenControllerTest {

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var userTenantService: UserTenantService

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @InjectMocks
    private lateinit var controller: TokenController

    private lateinit var testUser: SysUser

    @BeforeEach
    fun setUp() {
        testUser = SysUser().apply {
            id = 1L
            username = "admin"
            nickname = "Administrator"
            isAdmin = 1
            status = 1
        }
        // 初始化 SecurityUtils 单例,使 SecurityUtils.getCurrentUser() 可用
        SecurityUtils(sysUserMapper).init()
        `when`(sysUserMapper.selectByUsername("admin")).thenReturn(testUser)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        RequestContextHolder.resetRequestAttributes()
    }

    private fun loginAs(username: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(username, null, emptyList())
    }

    @Nested
    @DisplayName("POST /api/admin/auth/refresh-token")
    inner class RefreshTokenEndpoint {

        @Test
        @DisplayName("refreshToken - 未登录时返回错误")
        fun `refreshToken should return error when not logged in`() {
            SecurityContextHolder.clearContext()

            val result = controller.refreshToken()

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
        }

        @Test
        @DisplayName("refreshToken - 无请求上下文时 tenantId 为 null 并刷新成功")
        fun `refreshToken should succeed with null tenantId when no request context`() {
            loginAs("admin")
            RequestContextHolder.resetRequestAttributes()
            `when`(jwtUtil.generateToken(1L, "admin", null, 1)).thenReturn("new-token")
            `when`(jwtUtil.getExpirationTime()).thenReturn(7200000L)

            val result = controller.refreshToken()

            assertTrue(result.isSuccess())
            assertEquals("new-token", result.data?.get("accessToken"))
            assertNull(result.data?.get("tenantId"))
            assertEquals(7200L, result.data?.get("expiresIn"))
            verify(jwtUtil).generateToken(1L, "admin", null, 1)
        }

        @Test
        @DisplayName("refreshToken - 携带 Bearer token 时继承租户上下文")
        fun `refreshToken should inherit tenant from current token`() {
            loginAs("admin")
            val request = MockHttpServletRequest().apply {
                addHeader("Authorization", "Bearer old-token")
            }
            RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
            `when`(jwtUtil.getTenantIdFromToken("old-token")).thenReturn(88L)
            `when`(jwtUtil.generateToken(1L, "admin", 88L, 1)).thenReturn("new-token")
            `when`(jwtUtil.getExpirationTime()).thenReturn(3600000L)

            val result = controller.refreshToken()

            assertTrue(result.isSuccess())
            assertEquals("new-token", result.data?.get("accessToken"))
            assertEquals(88L, result.data?.get("tenantId"))
            assertEquals(3600L, result.data?.get("expiresIn"))
            verify(jwtUtil).getTenantIdFromToken("old-token")
        }

        @Test
        @DisplayName("refreshToken - Authorization 头不是 Bearer 格式时 tenantId 为 null")
        fun `refreshToken should ignore non-bearer authorization header`() {
            loginAs("admin")
            val request = MockHttpServletRequest().apply {
                addHeader("Authorization", "Basic dXNlcjpwYXNz")
            }
            RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
            `when`(jwtUtil.generateToken(1L, "admin", null, 1)).thenReturn("new-token")
            `when`(jwtUtil.getExpirationTime()).thenReturn(7200000L)

            val result = controller.refreshToken()

            assertTrue(result.isSuccess())
            assertNull(result.data?.get("tenantId"))
            verify(jwtUtil).generateToken(1L, "admin", null, 1)
        }

        @Test
        @DisplayName("refreshToken - 生成 token 抛异常时返回错误")
        fun `refreshToken should return error when token generation fails`() {
            loginAs("admin")
            RequestContextHolder.resetRequestAttributes()
            `when`(jwtUtil.generateToken(1L, "admin", null, 1))
                .thenThrow(RuntimeException("Signing key error"))

            val result = controller.refreshToken()

            assertFalse(result.isSuccess())
            assertEquals("Signing key error", result.message)
        }
    }
}
