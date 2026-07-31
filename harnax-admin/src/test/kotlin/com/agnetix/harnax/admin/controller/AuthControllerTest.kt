package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.CaptchaResponse
import com.agnetix.harnax.admin.dto.LoginRequest
import com.agnetix.harnax.admin.dto.LoginResponse
import com.agnetix.harnax.admin.dto.request.SwitchTenantRequest
import com.agnetix.harnax.admin.dto.response.TenantResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.AuthService
import com.agnetix.harnax.admin.service.CaptchaService
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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * AuthController 单元测试
 * 直接实例化 Controller + Mock Service，断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthControllerTest {

    @Mock
    private lateinit var authService: AuthService

    @Mock
    private lateinit var captchaService: CaptchaService

    @Mock
    private lateinit var userTenantService: UserTenantService

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @InjectMocks
    private lateinit var controller: AuthController

    private lateinit var testUser: SysUser

    @BeforeEach
    fun setUp() {
        // SecurityUtils 通过静态 instance 委托到 sysUserMapper
        SecurityUtils(sysUserMapper).init()

        testUser = SysUser().apply {
            id = 1L
            username = "admin"
            nickname = "Administrator"
            isAdmin = 0
            tenantId = 1L
        }
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    private fun mockLoggedInUser(user: SysUser) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(user.username, null, emptyList())
        `when`(sysUserMapper.selectByUsername(user.username)).thenReturn(user)
    }

    @Nested
    @DisplayName("POST /api/admin/auth/login")
    inner class LoginEndpoint {

        @Test
        @DisplayName("login - 登录成功返回 LoginResponse")
        fun `login should return login response on success`() {
            val request = LoginRequest(username = "admin", password = "admin123")
            val response = LoginResponse(
                accessToken = "mock-token",
                tokenType = "Bearer",
                expiresIn = 7200L,
            )
            `when`(authService.login(request)).thenReturn(response)

            val result = controller.login(request)

            assertTrue(result.isSuccess())
            assertEquals(200, result.code)
            assertEquals("mock-token", result.data?.accessToken)
            assertEquals("Bearer", result.data?.tokenType)
        }

        @Test
        @DisplayName("login - service 抛异常时异常向外传播")
        fun `login should propagate exception when service throws`() {
            val request = LoginRequest(username = "admin", password = "wrong")
            `when`(authService.login(request)).thenThrow(BizException("Invalid username or password"))

            assertThrows<BizException> {
                controller.login(request)
            }
        }
    }

    @Nested
    @DisplayName("POST /api/admin/auth/logout")
    inner class LogoutEndpoint {

        @Test
        @DisplayName("logout - 退出成功")
        fun `logout should return success`() {
            val result = controller.logout()

            assertTrue(result.isSuccess())
            assertEquals(200, result.code)
            verify(authService).logout()
        }

        @Test
        @DisplayName("logout - service 抛异常时异常向外传播")
        fun `logout should propagate exception when service throws`() {
            `when`(authService.logout()).thenThrow(RuntimeException("Redis unavailable"))

            assertThrows<RuntimeException> {
                controller.logout()
            }
        }
    }

    @Nested
    @DisplayName("GET /api/admin/auth/captcha")
    inner class CaptchaEndpoint {

        @Test
        @DisplayName("getCaptcha - 获取验证码成功")
        fun `getCaptcha should return captcha response`() {
            val captcha = CaptchaResponse(
                imageBase64 = "data:image/png;base64,abc",
                captchaKey = "uuid-key",
                expiresIn = 300L,
            )
            `when`(captchaService.generateCaptcha()).thenReturn(captcha)

            val result = controller.getCaptcha()

            assertTrue(result.isSuccess())
            assertEquals("uuid-key", result.data?.captchaKey)
            assertEquals(300L, result.data?.expiresIn)
        }

        @Test
        @DisplayName("getCaptcha - service 抛异常返回 error")
        fun `getCaptcha should return error when service throws`() {
            `when`(captchaService.generateCaptcha()).thenThrow(RuntimeException("Image generation failed"))

            val result = controller.getCaptcha()

            assertFalse(result.isSuccess())
            assertEquals(500, result.code)
            assertEquals("Image generation failed", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/auth/login-methods")
    inner class LoginMethodsEndpoint {

        @Test
        @DisplayName("getLoginMethods - 返回当前版本支持的登录方式")
        fun `getLoginMethods should return supported methods`() {
            val result = controller.getLoginMethods()

            assertTrue(result.isSuccess())
            @Suppress("UNCHECKED_CAST")
            val methods = result.data?.get("methods") as List<String>
            assertTrue(methods.contains("username"))
            assertTrue(methods.contains("phone"))
            assertTrue(methods.contains("email"))
        }
    }

    @Nested
    @DisplayName("GET /api/admin/auth/tenants")
    inner class UserTenantsEndpoint {

        @Test
        @DisplayName("getUserTenants - 已登录时返回租户列表")
        fun `getUserTenants should return tenant list when logged in`() {
            mockLoggedInUser(testUser)
            val tenants = listOf(
                TenantResponse(id = 1L, name = "Tenant A", status = 1),
                TenantResponse(id = 2L, name = "Tenant B", status = 1),
            )
            `when`(userTenantService.getUserTenants(1L)).thenReturn(tenants)

            val result = controller.getUserTenants()

            assertTrue(result.isSuccess())
            assertEquals(2, result.data?.size)
            assertEquals("Tenant A", result.data?.get(0)?.name)
        }

        @Test
        @DisplayName("getUserTenants - 未登录时返回 error")
        fun `getUserTenants should return error when not logged in`() {
            // 不设置 SecurityContext，getCurrentUser 返回 null
            val result = controller.getUserTenants()

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
        }

        @Test
        @DisplayName("getUserTenants - service 抛异常返回 error")
        fun `getUserTenants should return error when service throws`() {
            mockLoggedInUser(testUser)
            `when`(userTenantService.getUserTenants(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.getUserTenants()

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/auth/switch-tenant")
    inner class SwitchTenantEndpoint {

        @Test
        @DisplayName("switchTenant - 用户属于租户时切换成功")
        fun `switchTenant should return new token when user is in tenant`() {
            mockLoggedInUser(testUser)
            `when`(userTenantService.isUserInTenant(1L, 2L)).thenReturn(true)
            `when`(jwtUtil.generateToken(1L, "admin", 2L, 0)).thenReturn("new-token")

            val result = controller.switchTenant(SwitchTenantRequest(tenantId = 2L))

            assertTrue(result.isSuccess())
            assertEquals("new-token", result.data?.get("accessToken"))
            assertEquals(2L, result.data?.get("tenantId"))
        }

        @Test
        @DisplayName("switchTenant - 全局管理员可切换任意租户")
        fun `switchTenant should allow admin even if not in tenant`() {
            val adminUser = SysUser().apply {
                id = 9L
                username = "root"
                isAdmin = 1
            }
            mockLoggedInUser(adminUser)
            `when`(userTenantService.isUserInTenant(9L, 3L)).thenReturn(false)
            `when`(jwtUtil.generateToken(9L, "root", 3L, 1)).thenReturn("admin-token")

            val result = controller.switchTenant(SwitchTenantRequest(tenantId = 3L))

            assertTrue(result.isSuccess())
            assertEquals("admin-token", result.data?.get("accessToken"))
        }

        @Test
        @DisplayName("switchTenant - 无权限访问该租户时返回 error")
        fun `switchTenant should return error when no access to tenant`() {
            mockLoggedInUser(testUser)
            `when`(userTenantService.isUserInTenant(1L, 3L)).thenReturn(false)

            val result = controller.switchTenant(SwitchTenantRequest(tenantId = 3L))

            assertFalse(result.isSuccess())
            assertEquals("No access to this tenant", result.message)
        }

        @Test
        @DisplayName("switchTenant - 未登录时返回 error")
        fun `switchTenant should return error when not logged in`() {
            val result = controller.switchTenant(SwitchTenantRequest(tenantId = 2L))

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
        }

        @Test
        @DisplayName("switchTenant - service 抛异常返回 error")
        fun `switchTenant should return error when service throws`() {
            mockLoggedInUser(testUser)
            `when`(userTenantService.isUserInTenant(any(), any())).thenThrow(RuntimeException("DB error"))

            val result = controller.switchTenant(SwitchTenantRequest(tenantId = 2L))

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }
}
