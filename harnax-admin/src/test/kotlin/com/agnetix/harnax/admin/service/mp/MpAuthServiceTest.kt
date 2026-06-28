package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.dto.LoginRequest
import com.agnetix.harnax.admin.dto.response.TenantResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.ApiKeyService
import com.agnetix.harnax.admin.service.UserTenantService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.SysUserMapper
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
import org.mockito.quality.Strictness

/**
 * MpAuthService 单元测试
 * 测试移动端登录使用永久 API Key 的逻辑
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MpAuthServiceTest {

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @Mock
    private lateinit var userTenantService: UserTenantService

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var apiKeyService: ApiKeyService

    @Mock
    private lateinit var messageUtil: MessageUtil

    @InjectMocks
    private lateinit var mpAuthService: MpAuthService

    private lateinit var testUser: SysUser
    private lateinit var loginRequest: LoginRequest

    @BeforeEach
    fun setUp() {
        testUser = SysUser().apply {
            id = 1L
            username = "mobileuser"
            password = BCrypt.hashpw("password123", BCrypt.gensalt())
            nickname = "移动用户"
            email = "mobile@example.com"
            phone = "13800138000"
            gender = 1
            status = 1
            isAdmin = 0
            active = 1
        }

        loginRequest = LoginRequest(
            username = "mobileuser",
            password = "password123",
        )

        `when`(messageUtil.getMessage(anyString())).thenAnswer { it.arguments[0] as String }
        `when`(messageUtil.getMessage(anyString(), any())).thenAnswer { it.arguments[0] as String }

        `when`(userTenantService.getUserTenants(anyLong())).thenReturn(
            listOf(TenantResponse(id = 1L, name = "Default Tenant", status = 1)),
        )

        `when`(jwtUtil.generateToken(anyLong(), anyString(), any(), any())).thenReturn("mock-jwt-token")
        `when`(jwtUtil.getExpirationTime()).thenReturn(3600000L)

        `when`(apiKeyService.getPermanentRawKey(anyLong())).thenReturn("hnx_sk_live_mobile_permanent_key")
    }

    @Nested
    @DisplayName("移动端登录测试")
    inner class LoginTests {

        @Test
        @DisplayName("login - 登录成功并返回永久API Key")
        fun `login should succeed and return permanent API key`() {
            // Given
            `when`(sysUserMapper.selectByUsername("mobileuser")).thenReturn(testUser)
            `when`(sysUserMapper.updateLastLoginTime(anyLong(), any())).thenReturn(1)

            // When
            val response = mpAuthService.login(loginRequest)

            // Then
            assertNotNull(response)
            assertEquals("mock-jwt-token", response.accessToken)
            assertEquals("hnx_sk_live_mobile_permanent_key", response.routerApiKey)
            assertEquals(1L, response.userInfo.userId)
            assertEquals("mobileuser", response.userInfo.username)

            // 验证查询了永久 key（不再每次登录创建新 key）
            verify(apiKeyService, times(1)).getPermanentRawKey(1L)
        }

        @Test
        @DisplayName("login - 永久Key不存在时抛出异常")
        fun `login should throw when permanent key not found`() {
            // Given
            `when`(sysUserMapper.selectByUsername("mobileuser")).thenReturn(testUser)
            `when`(apiKeyService.getPermanentRawKey(1L)).thenReturn(null)

            // When & Then
            assertThrows<BizException> {
                mpAuthService.login(loginRequest)
            }
        }

        @Test
        @DisplayName("login - 用户不存在抛出异常")
        fun `login should throw when user not found`() {
            `when`(sysUserMapper.selectByUsername("mobileuser")).thenReturn(null)

            assertThrows<BizException> {
                mpAuthService.login(loginRequest)
            }
        }

        @Test
        @DisplayName("login - 密码错误抛出异常")
        fun `login should throw when password is wrong`() {
            `when`(sysUserMapper.selectByUsername("mobileuser")).thenReturn(testUser)

            val wrongRequest = loginRequest.copy(password = "wrongpassword")

            assertThrows<BizException> {
                mpAuthService.login(wrongRequest)
            }
        }

        @Test
        @DisplayName("login - 用户被禁用抛出异常")
        fun `login should throw when user is disabled`() {
            val disabledUser = testUser.apply { status = 0 }
            `when`(sysUserMapper.selectByUsername("mobileuser")).thenReturn(disabledUser)

            assertThrows<BizException> {
                mpAuthService.login(loginRequest)
            }
        }

        @Test
        @DisplayName("login - 密码为空抛出异常")
        fun `login should throw when password is empty`() {
            val emptyPasswordRequest = loginRequest.copy(password = "")

            assertThrows<BizException> {
                mpAuthService.login(emptyPasswordRequest)
            }
        }

        @Test
        @DisplayName("login - 更新登录时间失败不影响登录成功")
        fun `login should succeed even when update login time fails`() {
            // Given
            `when`(sysUserMapper.selectByUsername("mobileuser")).thenReturn(testUser)
            `when`(sysUserMapper.updateLastLoginTime(anyLong(), any())).thenThrow(RuntimeException("DB error"))

            // When
            val response = mpAuthService.login(loginRequest)

            // Then
            assertNotNull(response)
            assertEquals("hnx_sk_live_mobile_permanent_key", response.routerApiKey)
        }
    }
}
