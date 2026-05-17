package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.config.EditionUtil
import com.agnetix.harnax.admin.dto.LoginRequest
import com.agnetix.harnax.admin.dto.response.TenantResponse
import com.agnetix.harnax.admin.entity.SysUser
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.mapper.SysUserMapper
import com.agnetix.harnax.admin.mapper.TenantMapper
import com.agnetix.harnax.admin.service.CaptchaService
import com.agnetix.harnax.admin.service.SysTokenBlacklistService
import com.agnetix.harnax.admin.service.UserTenantService
import com.agnetix.harnax.admin.util.JwtUtil
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
import org.mockito.ArgumentMatchers.eq
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
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
    private lateinit var editionUtil: EditionUtil

    @Mock
    private lateinit var tenantMapper: TenantMapper

    @Mock
    private lateinit var messageUtil: MessageUtil

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

        // Mock editionUtil to return true (personal mode)
        `when`(editionUtil.isPersonal()).thenReturn(true)

        // Mock tenantMapper to return default tenant
        `when`(tenantMapper.selectById(1)).thenReturn(
            com.agnetix.harnax.admin.entity.TenantEntity().apply {
                id = 1L
                name = "Default Tenant"
            },
        )

        // Mock userTenantService.addUserToTenant
        `when`(userTenantService.addUserToTenant(anyLong(), anyLong(), anyString(), anyString())).thenReturn(true)
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

            // 验证更新了登录时间
            verify(sysUserMapper, times(1)).updateLastLoginTime(eq(1L), any())
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
    }
}
