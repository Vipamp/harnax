package com.agnetix.harnax.admin.controller.mp

import com.agnetix.harnax.admin.dto.mp.MpChangePasswordRequest
import com.agnetix.harnax.admin.dto.mp.MpUserProfileResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.mp.MpUserService
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
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.LocalDateTime

/**
 * MpUserController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MpUserControllerTest {

    @Mock
    private lateinit var mpUserService: MpUserService

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @InjectMocks
    private lateinit var controller: MpUserController

    private lateinit var testUser: SysUser

    @BeforeEach
    fun setUp() {
        testUser = SysUser().apply {
            id = 1L
            username = "mpuser"
            nickname = "MP User"
            status = 1
        }
        // 初始化 SecurityUtils 单例,使 SecurityUtils.getCurrentUser() 可用
        SecurityUtils(sysUserMapper).init()
        `when`(sysUserMapper.selectByUsername("mpuser")).thenReturn(testUser)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    private fun loginAs(username: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(username, null, emptyList())
    }

    @Nested
    @DisplayName("GET /api/admin/mp/user/profile")
    inner class GetProfileEndpoint {

        @Test
        @DisplayName("getProfile - 已登录时返回用户资料")
        fun `getProfile should return profile when logged in`() {
            loginAs("mpuser")
            val profile = MpUserProfileResponse(
                userId = 1L,
                username = "mpuser",
                nickname = "MP User",
                avatar = "http://avatar.url/1.png",
                email = "mp@test.com",
                phone = "13800000000",
                createTime = LocalDateTime.now(),
            )
            `when`(mpUserService.getProfile(1L)).thenReturn(profile)

            val result = controller.getProfile()

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.userId)
            assertEquals("mpuser", result.data?.username)
            assertEquals("MP User", result.data?.nickname)
            assertEquals("mp@test.com", result.data?.email)
        }

        @Test
        @DisplayName("getProfile - 未登录时返回错误")
        fun `getProfile should return error when not logged in`() {
            SecurityContextHolder.clearContext()

            val result = controller.getProfile()

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
            verify(mpUserService, never()).getProfile(any())
        }

        @Test
        @DisplayName("getProfile - 用户不存在时异常向上传播")
        fun `getProfile should propagate exception when user not found`() {
            loginAs("mpuser")
            `when`(mpUserService.getProfile(1L)).thenThrow(BizException("User not found"))

            assertThrows<BizException> {
                controller.getProfile()
            }
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/mp/user/password")
    inner class ChangePasswordEndpoint {

        private val request = MpChangePasswordRequest(
            oldPassword = "old-hash",
            newPassword = "new-hash",
        )

        @Test
        @DisplayName("changePassword - 已登录时修改成功")
        fun `changePassword should return success when logged in`() {
            loginAs("mpuser")

            val result = controller.changePassword(request)

            assertTrue(result.isSuccess())
            verify(mpUserService).changePassword(1L, request)
        }

        @Test
        @DisplayName("changePassword - 未登录时返回错误")
        fun `changePassword should return error when not logged in`() {
            SecurityContextHolder.clearContext()

            val result = controller.changePassword(request)

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
            verify(mpUserService, never()).changePassword(any(), any())
        }

        @Test
        @DisplayName("changePassword - 旧密码错误时异常向上传播")
        fun `changePassword should propagate exception on wrong old password`() {
            loginAs("mpuser")
            doThrow(BizException("Invalid credentials"))
                .`when`(mpUserService).changePassword(eq(1L), any())

            assertThrows<BizException> {
                controller.changePassword(request)
            }
        }
    }
}
