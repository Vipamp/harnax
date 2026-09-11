package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.LoginRequest
import com.agnetix.harnax.admin.dto.response.TenantResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.ApiKeyService
import com.agnetix.harnax.admin.service.CaptchaService
import com.agnetix.harnax.admin.service.SysTokenBlacklistService
import com.agnetix.harnax.admin.service.UserTenantService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.entity.TenantEntity
import com.agnetix.harnax.mapper.SysUserMapper
import com.agnetix.harnax.mapper.TenantMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mindrot.jbcrypt.BCrypt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness

/**
 * AuthServiceImpl 单元测试
 * 使用 Mockito 模拟依赖
 *
 * @author agnetix
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceImplTest {

    @Mock
    private lateinit var sysUserService: com.agnetix.harnax.admin.service.SysUserService

    @Mock
    private lateinit var captchaService: CaptchaService

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var tokenBlacklistService: SysTokenBlacklistService

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @Mock
    private lateinit var userTenantService: UserTenantService

    @Mock
    private lateinit var tenantMapper: TenantMapper

    @Mock
    private lateinit var messageUtil: MessageUtil

    @Mock
    private lateinit var apiKeyService: ApiKeyService

    @InjectMocks
    private lateinit var authService: AuthServiceImpl

    private lateinit var testUser: SysUser
    private lateinit var loginRequest: LoginRequest

    @BeforeEach
    fun setUp() {
        // 准备测试用户
        testUser = SysUser().apply {
            id = 1L
            username = "testuser"
            password = BCrypt.hashpw("password123", BCrypt.gensalt()) // BCrypt 加密后的密码
            nickname = "测试用户"
            email = "test@example.com"
            phone = "13800138000"
            gender = 1
            status = 1
            isAdmin = 0
            active = 1
        }

        // 准备登录请求
        loginRequest = LoginRequest(
            username = "testuser",
            password = "password123", // 明文密码（实际项目中前端会先 SHA-256 加密）
            captcha = "ABCD",
            captchaKey = "captcha-key-123",
        )

        // Mock messageUtil to return the key as message
        `when`(messageUtil.getMessage(anyString())).thenAnswer { it.arguments[0] as String }
        `when`(messageUtil.getMessage(anyString(), any())).thenAnswer { it.arguments[0] as String }

        // Mock userTenantService to return a tenant list
        `when`(userTenantService.getUserTenants(anyLong())).thenReturn(
            listOf(
                TenantResponse(
                    id = 1L,
                    name = "Default Tenant",
                    status = 1,
                ),
            ),
        )

        // Mock userTenantService.addUserToTenant
        `when`(userTenantService.addUserToTenant(anyLong(), anyLong(), anyString(), anyString())).thenReturn(true)

        // Mock tenantMapper to return default tenant
        `when`(tenantMapper.selectById(1)).thenReturn(
            TenantEntity().apply {
                id = 1L
                name = "Default Tenant"
            },
        )

        // Mock userTenantService.addUserToTenant
        `when`(userTenantService.addUserToTenant(anyLong(), anyLong(), anyString(), anyString())).thenReturn(true)

        // Mock apiKeyService to return a permanent raw key
        `when`(apiKeyService.getPermanentRawKey(anyLong())).thenReturn("hnx_sk_live_test_permanent_key_1234567890")
    }

    @Nested
    @DisplayName("登录测试")
    inner class LoginTests {

        @Test
        @DisplayName("login - 登录成功并更新登录时间")
        fun `login should succeed and update last login time`() {
            // Given
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)
            `when`(captchaService.validateCaptcha("captcha-key-123", "ABCD")).thenReturn(true)
            `when`(jwtUtil.generateToken(anyLong(), anyString(), any(), anyInt())).thenReturn("mock-jwt-token")
            `when`(jwtUtil.getExpirationTime()).thenReturn(3600000L) // 1小时
            `when`(sysUserMapper.updateLastLoginTime(anyLong(), any())).thenReturn(1)

            // When
            val response = authService.login(loginRequest)

            // Then
            assertNotNull(response)
            assertEquals("mock-jwt-token", response.accessToken)
            assertEquals("Bearer", response.tokenType)
            assertNotNull(response.userInfo)
            assertEquals("testuser", response.userInfo?.username)
            assertEquals("测试用户", response.userInfo?.nickname)

            // 验证返回了永久 API key
            assertEquals("hnx_sk_live_test_permanent_key_1234567890", response.routerApiKey)

            // 验证更新了登录时间
            verify(sysUserMapper, times(1)).updateLastLoginTime(eq(1L), any())

            // 验证查询了永久 key（不再每次登录创建新 key）
            verify(apiKeyService, times(1)).getPermanentRawKey(1L)
        }

        @Test
        @DisplayName("login - 用户不存在抛出异常")
        fun `login should throw exception when user not found`() {
            // Given
            `when`(sysUserService.getByUsername("testuser")).thenReturn(null)

            // When & Then
            assertThrows<BizException> {
                authService.login(loginRequest)
            }

            // 验证不会更新登录时间
            verify(sysUserMapper, never()).updateLastLoginTime(anyLong(), any())
        }

        @Test
        @DisplayName("login - 密码错误抛出异常")
        fun `login should throw exception when password is wrong`() {
            // Given
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)

            // 使用错误的密码
            val wrongRequest = loginRequest.copy(password = "wrongpassword")

            // When & Then
            assertThrows<BizException> {
                authService.login(wrongRequest)
            }

            // 验证不会更新登录时间
            verify(sysUserMapper, never()).updateLastLoginTime(anyLong(), any())
        }

        @Test
        @DisplayName("login - 用户被禁用抛出异常")
        fun `login should throw exception when user is disabled`() {
            // Given
            val disabledUser = testUser.apply { status = 0 }
            `when`(sysUserService.getByUsername("testuser")).thenReturn(disabledUser)

            // When & Then
            assertThrows<BizException> {
                authService.login(loginRequest)
            }

            // 验证不会更新登录时间
            verify(sysUserMapper, never()).updateLastLoginTime(anyLong(), any())
        }

        @Test
        @DisplayName("login - 验证码错误抛出异常")
        fun `login should throw exception when captcha is wrong`() {
            // Given
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)
            `when`(captchaService.validateCaptcha("captcha-key-123", "ABCD")).thenReturn(false)

            // When & Then
            assertThrows<BizException> {
                authService.login(loginRequest)
            }

            // 验证不会更新登录时间
            verify(sysUserMapper, never()).updateLastLoginTime(anyLong(), any())
        }

        @Test
        @DisplayName("login - 永久Key缺失时自愈补建后登录成功")
        fun `login should self-heal by creating permanent key when missing`() {
            // Given
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)
            `when`(captchaService.validateCaptcha("captcha-key-123", "ABCD")).thenReturn(true)
            `when`(jwtUtil.generateToken(anyLong(), anyString(), any(), anyInt())).thenReturn("mock-jwt-token")
            `when`(jwtUtil.getExpirationTime()).thenReturn(3600000L)
            `when`(apiKeyService.getPermanentRawKey(1L)).thenReturn(null)
            `when`(apiKeyService.createPermanentKeyForUser(anyLong(), anyString(), org.mockito.kotlin.anyOrNull())).thenReturn(
                com.agnetix.harnax.admin.dto.ApiKeyCreatedResponse(
                    id = 100L,
                    name = "permanent_testuser",
                    rawKey = "hnx_sk_live_recreated_key",
                    keyPrefix = "hnx_sk_live_...key",
                ),
            )

            // When
            val response = authService.login(loginRequest)

            // Then
            assertNotNull(response)
            assertEquals("hnx_sk_live_recreated_key", response.routerApiKey)
            verify(apiKeyService, times(1)).createPermanentKeyForUser(org.mockito.kotlin.eq(1L), org.mockito.kotlin.eq("testuser"), org.mockito.kotlin.anyOrNull())
        }

        @Test
        @DisplayName("login - 永久Key缺失且补建失败抛出异常")
        fun `login should throw exception when permanent key not found and creation fails`() {
            // Given
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)
            `when`(captchaService.validateCaptcha("captcha-key-123", "ABCD")).thenReturn(true)
            `when`(jwtUtil.generateToken(anyLong(), anyString(), any(), anyInt())).thenReturn("mock-jwt-token")
            `when`(jwtUtil.getExpirationTime()).thenReturn(3600000L)
            `when`(apiKeyService.getPermanentRawKey(1L)).thenReturn(null)
            `when`(apiKeyService.createPermanentKeyForUser(anyLong(), anyString(), org.mockito.kotlin.anyOrNull()))
                .thenThrow(RuntimeException("insert failed"))

            // When & Then
            assertThrows<BizException> {
                authService.login(loginRequest)
            }

            // 验证查询了永久 key（初查一次 + 补建失败后重查一次）
            verify(apiKeyService, times(2)).getPermanentRawKey(1L)
            verify(apiKeyService, times(1)).createPermanentKeyForUser(org.mockito.kotlin.eq(1L), org.mockito.kotlin.eq("testuser"), org.mockito.kotlin.anyOrNull())
        }

        @Test
        @DisplayName("login - 更新登录时间失败不影响登录成功")
        fun `login should succeed even when update last login time fails`() {
            // Given
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)
            `when`(captchaService.validateCaptcha("captcha-key-123", "ABCD")).thenReturn(true)
            `when`(jwtUtil.generateToken(anyLong(), anyString(), any(), anyInt())).thenReturn("mock-jwt-token")
            `when`(jwtUtil.getExpirationTime()).thenReturn(3600000L)
            `when`(sysUserMapper.updateLastLoginTime(anyLong(), any())).thenThrow(RuntimeException("DB error"))

            // When
            val response = authService.login(loginRequest)

            // Then
            assertNotNull(response)
            assertEquals("mock-jwt-token", response.accessToken)

            // 验证尝试更新登录时间（即使失败也不影响登录）
            verify(sysUserMapper, times(1)).updateLastLoginTime(eq(1L), any())
        }

        @Test
        @DisplayName("login - 验证码为空抛出异常且不调用验证码服务")
        fun `login should throw exception when captcha is blank`() {
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)

            val ex = assertThrows<BizException> {
                authService.login(loginRequest.copy(captcha = "  "))
            }
            assertEquals("error.captcha.required", ex.message)
            verify(captchaService, never()).validateCaptcha(anyString(), anyString())
        }

        @Test
        @DisplayName("login - 验证码key为空抛出异常")
        fun `login should throw exception when captcha key is blank`() {
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)

            val ex = assertThrows<BizException> {
                authService.login(loginRequest.copy(captchaKey = null))
            }
            assertEquals("error.captcha.key_required", ex.message)
            verify(captchaService, never()).validateCaptcha(anyString(), anyString())
        }

        @Test
        @DisplayName("login - 普通用户无租户抛出异常")
        fun `login should throw exception when non-admin user has no tenant`() {
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)
            `when`(captchaService.validateCaptcha("captcha-key-123", "ABCD")).thenReturn(true)
            `when`(userTenantService.getUserTenants(1L)).thenReturn(emptyList())

            val ex = assertThrows<BizException> {
                authService.login(loginRequest)
            }
            assertEquals("error.user.no_tenant", ex.message)
            verify(jwtUtil, never()).generateToken(anyLong(), anyString(), any(), anyInt())
        }

        @Test
        @DisplayName("login - admin无租户可登录且token的tenantId为null")
        fun `login should succeed for admin without tenant using null tenantId`() {
            val adminUser = testUser.apply { isAdmin = 1 }
            `when`(sysUserService.getByUsername("testuser")).thenReturn(adminUser)
            `when`(captchaService.validateCaptcha("captcha-key-123", "ABCD")).thenReturn(true)
            `when`(userTenantService.getUserTenants(1L)).thenReturn(emptyList())
            `when`(jwtUtil.generateToken(anyLong(), anyString(), org.mockito.kotlin.anyOrNull(), anyInt())).thenReturn("admin-token")
            `when`(jwtUtil.getExpirationTime()).thenReturn(3600000L)

            val response = authService.login(loginRequest)

            assertEquals("admin-token", response.accessToken)
            assertNull(response.currentTenantId)
            verify(jwtUtil).generateToken(eq(1L), eq("testuser"), org.mockito.kotlin.isNull(), eq(1))
        }

        @Test
        @DisplayName("login - 补建冲突后重查成功(并发自愈)")
        fun `login should recover via retry lookup when concurrent creation conflicts`() {
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)
            `when`(captchaService.validateCaptcha("captcha-key-123", "ABCD")).thenReturn(true)
            `when`(jwtUtil.generateToken(anyLong(), anyString(), any(), anyInt())).thenReturn("mock-jwt-token")
            `when`(jwtUtil.getExpirationTime()).thenReturn(3600000L)
            // 初查 null → 补建抛唯一键冲突 → 重查命中(另一并发请求已创建)
            `when`(apiKeyService.getPermanentRawKey(1L))
                .thenReturn(null)
                .thenReturn("hnx_sk_live_created_by_concurrent_login")
            `when`(apiKeyService.createPermanentKeyForUser(anyLong(), anyString(), org.mockito.kotlin.anyOrNull()))
                .thenThrow(RuntimeException("Duplicate entry uk_user_permanent"))

            val response = authService.login(loginRequest)

            assertEquals("hnx_sk_live_created_by_concurrent_login", response.routerApiKey)
            verify(apiKeyService, times(2)).getPermanentRawKey(1L)
        }
    }

    @Nested
    @DisplayName("移动端登录测试")
    inner class MobileLoginTests {

        @Test
        @DisplayName("mobileLogin - 免验证码登录成功")
        fun `mobileLogin should succeed without captcha`() {
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)
            `when`(jwtUtil.generateToken(anyLong(), anyString(), any(), anyInt())).thenReturn("mobile-token")
            `when`(jwtUtil.getExpirationTime()).thenReturn(3600000L)

            // 不带验证码字段
            val response = authService.mobileLogin(loginRequest.copy(captcha = null, captchaKey = null))

            assertEquals("mobile-token", response.accessToken)
            assertEquals("Bearer", response.tokenType)
            assertEquals("testuser", response.userInfo?.username)
            // 免验证码:不调用验证码服务
            verify(captchaService, never()).validateCaptcha(anyString(), anyString())
            verify(sysUserMapper, times(1)).updateLastLoginTime(eq(1L), any())
        }

        @Test
        @DisplayName("mobileLogin - 用户不存在抛出异常")
        fun `mobileLogin should throw exception when user not found`() {
            `when`(sysUserService.getByUsername("testuser")).thenReturn(null)

            val ex = assertThrows<BizException> {
                authService.mobileLogin(loginRequest)
            }
            assertEquals("error.user.notfound", ex.message)
        }

        @Test
        @DisplayName("mobileLogin - 密码错误抛出异常")
        fun `mobileLogin should throw exception when password is wrong`() {
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)

            val ex = assertThrows<BizException> {
                authService.mobileLogin(loginRequest.copy(password = "wrongpassword"))
            }
            assertEquals("error.user.invalid_credentials", ex.message)
        }

        @Test
        @DisplayName("mobileLogin - 用户被禁用抛出异常")
        fun `mobileLogin should throw exception when user is disabled`() {
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser.apply { status = 0 })

            val ex = assertThrows<BizException> {
                authService.mobileLogin(loginRequest)
            }
            assertEquals("error.user.disabled", ex.message)
        }

        @Test
        @DisplayName("mobileLogin - 普通用户无租户抛出异常")
        fun `mobileLogin should throw exception when non-admin user has no tenant`() {
            `when`(sysUserService.getByUsername("testuser")).thenReturn(testUser)
            `when`(userTenantService.getUserTenants(1L)).thenReturn(emptyList())

            val ex = assertThrows<BizException> {
                authService.mobileLogin(loginRequest)
            }
            assertEquals("error.user.no_tenant", ex.message)
        }
    }

    @Nested
    @DisplayName("退出登录测试")
    inner class LogoutTests {

        @org.junit.jupiter.api.AfterEach
        fun tearDown() {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes()
        }

        private fun mockRequestWithAuth(header: String?) {
            val request = org.springframework.mock.web.MockHttpServletRequest()
            if (header != null) request.addHeader("Authorization", header)
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                org.springframework.web.context.request.ServletRequestAttributes(request),
            )
        }

        @Test
        @DisplayName("logout - 合法token加入黑名单")
        fun `logout should add token to blacklist`() {
            mockRequestWithAuth("Bearer valid-token")
            `when`(jwtUtil.getUserIdFromToken("valid-token")).thenReturn(1L)
            `when`(jwtUtil.getUsernameFromToken("valid-token")).thenReturn("testuser")
            `when`(jwtUtil.getExpirationTime()).thenReturn(3600000L)

            authService.logout()

            verify(tokenBlacklistService, times(1))
                .addToBlacklist(eq("valid-token"), eq("testuser"), eq(1L), any(), eq("logout"))
        }

        @Test
        @DisplayName("logout - 无Authorization头时不加黑名单且不抛异常")
        fun `logout without token should not touch blacklist`() {
            mockRequestWithAuth(null)

            authService.logout()

            verify(tokenBlacklistService, never()).addToBlacklist(anyString(), anyString(), anyLong(), any(), anyString())
        }

        @Test
        @DisplayName("logout - 非Bearer前缀视为无token")
        fun `logout with non-bearer header should not touch blacklist`() {
            mockRequestWithAuth("Basic dXNlcjpwYXNz")

            authService.logout()

            verify(tokenBlacklistService, never()).addToBlacklist(anyString(), anyString(), anyLong(), any(), anyString())
        }

        @Test
        @DisplayName("logout - token解析失败被吞掉不抛异常")
        fun `logout should swallow token parse errors`() {
            mockRequestWithAuth("Bearer expired-token")
            `when`(jwtUtil.getUserIdFromToken("expired-token")).thenThrow(RuntimeException("expired"))

            org.junit.jupiter.api.assertDoesNotThrow {
                authService.logout()
            }
            verify(tokenBlacklistService, never()).addToBlacklist(anyString(), anyString(), anyLong(), any(), anyString())
        }

        @Test
        @DisplayName("logout - 黑名单过期时间约为当前时间加token有效期")
        fun `logout should pass expire time near now plus expiration`() {
            mockRequestWithAuth("Bearer valid-token")
            `when`(jwtUtil.getUserIdFromToken("valid-token")).thenReturn(1L)
            `when`(jwtUtil.getUsernameFromToken("valid-token")).thenReturn("testuser")
            `when`(jwtUtil.getExpirationTime()).thenReturn(3600000L) // 1小时

            val before = java.time.LocalDateTime.now()
            authService.logout()
            val after = java.time.LocalDateTime.now()

            val captor = org.mockito.kotlin.argumentCaptor<java.time.LocalDateTime>()
            verify(tokenBlacklistService).addToBlacklist(eq("valid-token"), eq("testuser"), eq(1L), captor.capture(), eq("logout"))
            val expireTime = captor.firstValue
            // expireTime ∈ [before+1h, after+1h]
            assertFalse(expireTime.isBefore(before.plusHours(1)), "expireTime too early: $expireTime")
            assertFalse(expireTime.isAfter(after.plusHours(1)), "expireTime too late: $expireTime")
        }

        @Test
        @DisplayName("logout - 无请求上下文时不抛异常")
        fun `logout outside request context should not throw`() {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes()

            org.junit.jupiter.api.assertDoesNotThrow {
                authService.logout()
            }
            verify(tokenBlacklistService, never()).addToBlacklist(anyString(), anyString(), anyLong(), any(), anyString())
        }
    }
}
