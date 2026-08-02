package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.dto.mp.MpChangePasswordRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
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
import org.mockito.ArgumentMatchers.eq
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * MpUserService 单元测试
 * 测试移动端用户资料查询与修改密码逻辑
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MpUserServiceTest {

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @Mock
    private lateinit var messageUtil: MessageUtil

    @InjectMocks
    private lateinit var mpUserService: MpUserService

    private lateinit var testUser: SysUser

    @BeforeEach
    fun setUp() {
        testUser = SysUser().apply {
            id = 1L
            username = "mobileuser"
            password = BCrypt.hashpw("oldPassword123", BCrypt.gensalt())
            nickname = "移动用户"
            avatar = "http://img/avatar.png"
            email = "mobile@example.com"
            phone = "13800138000"
            status = 1
            active = 1
            createTime = LocalDateTime.of(2026, 1, 1, 0, 0)
        }

        `when`(messageUtil.getMessage(anyString())).thenAnswer { it.arguments[0] as String }
        `when`(messageUtil.getMessage(anyString(), any())).thenAnswer { it.arguments[0] as String }
    }

    @Nested
    @DisplayName("查询用户资料测试")
    inner class GetProfileTests {

        @Test
        @DisplayName("getProfile - 返回用户资料")
        fun `getProfile should return user profile`() {
            // Given
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)

            // When
            val profile = mpUserService.getProfile(1L)

            // Then
            assertEquals(1L, profile.userId)
            assertEquals("mobileuser", profile.username)
            assertEquals("移动用户", profile.nickname)
            assertEquals("http://img/avatar.png", profile.avatar)
            assertEquals("mobile@example.com", profile.email)
            assertEquals("13800138000", profile.phone)
            assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), profile.createTime)
        }

        @Test
        @DisplayName("getProfile - 用户不存在抛出异常")
        fun `getProfile should throw when user not found`() {
            // Given
            `when`(sysUserMapper.selectById(1L)).thenReturn(null)

            // When & Then
            val ex = assertThrows<BizException> {
                mpUserService.getProfile(1L)
            }
            assertEquals("error.user.notfound", ex.message)
        }
    }

    @Nested
    @DisplayName("修改密码测试")
    inner class ChangePasswordTests {

        private val changeRequest = MpChangePasswordRequest(
            oldPassword = "oldPassword123",
            newPassword = "newPassword456",
        )

        @Test
        @DisplayName("changePassword - 旧密码正确时更新为新密码的BCrypt哈希")
        fun `changePassword should update password with bcrypt hash when old password correct`() {
            // Given
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)

            // When
            mpUserService.changePassword(1L, changeRequest)

            // Then
            val captor = argumentCaptor<String>()
            verify(sysUserMapper, times(1)).updatePassword(eq(1L), captor.capture())
            val savedHash = captor.firstValue
            // 保存的是新密码的 BCrypt 哈希, 而不是明文
            assertNotEquals("newPassword456", savedHash)
            assertTrue(BCrypt.checkpw("newPassword456", savedHash))
        }

        @Test
        @DisplayName("changePassword - 用户不存在抛出异常")
        fun `changePassword should throw when user not found`() {
            // Given
            `when`(sysUserMapper.selectById(1L)).thenReturn(null)

            // When & Then
            val ex = assertThrows<BizException> {
                mpUserService.changePassword(1L, changeRequest)
            }
            assertEquals("error.user.notfound", ex.message)
            verify(sysUserMapper, never()).updatePassword(anyLong(), anyString())
        }

        @Test
        @DisplayName("changePassword - 旧密码错误抛出异常")
        fun `changePassword should throw when old password is wrong`() {
            // Given
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)

            // When & Then
            val ex = assertThrows<BizException> {
                mpUserService.changePassword(1L, changeRequest.copy(oldPassword = "wrongOldPassword"))
            }
            assertEquals("error.user.invalid_credentials", ex.message)
            verify(sysUserMapper, never()).updatePassword(anyLong(), anyString())
        }
    }
}
