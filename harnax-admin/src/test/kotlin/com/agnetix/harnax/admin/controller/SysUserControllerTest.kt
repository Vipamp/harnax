package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SysUserCreateRequest
import com.agnetix.harnax.admin.dto.SysUserResponse
import com.agnetix.harnax.admin.dto.SysUserUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.SysUserService
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.SysUserMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.LocalDateTime

/**
 * SysUserController 单元测试
 * 直接实例化 Controller + Mock Service，断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SysUserControllerTest {

    @Mock
    private lateinit var sysUserService: SysUserService

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @Mock
    private lateinit var messageUtil: MessageUtil

    @InjectMocks
    private lateinit var controller: SysUserController

    private lateinit var testUser: SysUser
    private lateinit var testResponse: SysUserResponse
    private lateinit var adminUser: SysUser
    private lateinit var memberUser: SysUser

    @BeforeEach
    fun setUp() {
        // MessageUtil 桩成回显消息码：断言只看键，不依赖 bundle 文案
        `when`(messageUtil.getMessage(anyString())).thenAnswer { invocation -> invocation.arguments[0] as String }
        // SecurityUtils 通过静态 instance 委托到 sysUserMapper
        SecurityUtils(sysUserMapper).init()
        adminUser = SysUser().apply {
            id = 1L
            username = "admin"
            isAdmin = 1
        }
        memberUser = SysUser().apply {
            id = 2L
            username = "zhangsan"
            isAdmin = 0
        }
        // Every endpoint here answers only to a global admin, so the cases below that are about other
        // behaviour start from an admin caller.
        mockLoggedInUser(adminUser)
        testUser = SysUser().apply {
            id = 1L
            username = "zhangsan"
            nickname = "Zhang San"
            email = "zhangsan@example.com"
            phone = "13800138000"
            gender = 2
            status = 1
            isAdmin = 0
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testResponse = SysUserResponse(
            id = 1L,
            username = "zhangsan",
            nickname = "Zhang San",
            email = "zhangsan@example.com",
            phone = "13800138000",
            gender = 2,
            status = 1,
            isAdmin = 0,
            lastLoginTime = null,
            createTime = testUser.createTime,
            updateTime = testUser.updateTime,
        )
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
    @DisplayName("GET /api/admin/users/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("pageSysUser - 返回分页结果")
        fun `pageSysUser should return paginated results`() {
            val page = Page<SysUser>(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testUser))
            `when`(sysUserService.page(null, null, null, 1, 10)).thenReturn(page)
            `when`(sysUserService.convertToResponse(testUser)).thenReturn(testResponse)

            val result = controller.pageSysUser(1, 10, null, null, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("zhangsan", result.data?.records?.get(0)?.username)
        }

        @Test
        @DisplayName("pageSysUser - 传递过滤条件")
        fun `pageSysUser should pass filters correctly`() {
            val page = Page<SysUser>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(sysUserService.page("zhang", 1, 2L, 1, 10)).thenReturn(page)

            val result = controller.pageSysUser(1, 10, "zhang", 1, 2L)

            assertTrue(result.isSuccess())
            verify(sysUserService).page("zhang", 1, 2L, 1, 10)
        }

        @Test
        @DisplayName("pageSysUser - pageNum/pageSize 为 null 时使用默认值")
        fun `pageSysUser should use default paging when null`() {
            val page = Page<SysUser>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(sysUserService.page(null, null, null, 1, 10)).thenReturn(page)

            val result = controller.pageSysUser(null, null, null, null, null)

            assertTrue(result.isSuccess())
            verify(sysUserService).page(null, null, null, 1, 10)
        }

        @Test
        @DisplayName("pageSysUser - service 抛异常返回 error")
        fun `pageSysUser should return error when service throws`() {
            `when`(sysUserService.page(null, null, null, 1, 10)).thenThrow(RuntimeException("DB error"))

            val result = controller.pageSysUser(1, 10, null, null, null)

            assertFalse(result.isSuccess())
            assertEquals(500, result.code)
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/users/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getSysUser - 返回用户详情")
        fun `getSysUser should return user details`() {
            `when`(sysUserService.getSysUser(1L)).thenReturn(testUser)
            `when`(sysUserService.convertToResponse(testUser)).thenReturn(testResponse)

            val result = controller.getSysUser(1L)

            assertTrue(result.isSuccess())
            assertEquals("zhangsan", result.data?.username)
            assertEquals("Zhang San", result.data?.nickname)
        }

        @Test
        @DisplayName("getSysUser - 读不到时返回具名 404")
        fun `getSysUser should report not found with a named 404`() {
            `when`(sysUserService.getSysUser(999L)).thenReturn(null)

            val result = controller.getSysUser(999L)

            assertFalse(result.isSuccess(), "读不到的行不能算成功响应")
            assertEquals(404, result.code)
            assertEquals("error.user.notfound", result.message)
            assertNull(result.data)
        }

        @Test
        @DisplayName("getSysUser - service 抛异常返回 error")
        fun `getSysUser should return error when service throws`() {
            `when`(sysUserService.getSysUser(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.getSysUser(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/users")
    inner class CreateEndpoint {

        @Test
        @DisplayName("createUser - 创建成功")
        fun `createUser should return success`() {
            val request = SysUserCreateRequest(
                username = "lisi",
                password = "password123",
                nickname = "Li Si",
                email = "lisi@example.com",
                phone = "13900139000",
            )
            `when`(sysUserService.createUser(any())).thenReturn(true)

            val result = controller.createUser(request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("createUser - service 返回 false 时返回 error")
        fun `createUser should return error when service returns false`() {
            val request = SysUserCreateRequest(username = "lisi", password = "password123", nickname = "Li Si")
            `when`(sysUserService.createUser(any())).thenReturn(false)

            val result = controller.createUser(request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to create user", result.message)
        }

        @Test
        @DisplayName("createUser - 用户名重复时返回 error")
        fun `createUser should return error on duplicate username`() {
            val request = SysUserCreateRequest(username = "zhangsan", password = "password123", nickname = "Zhang San")
            `when`(sysUserService.createUser(any())).thenThrow(BizException("Username already exists"))

            val result = controller.createUser(request)

            assertFalse(result.isSuccess())
            assertEquals("Username already exists", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/users/update/{id}")
    inner class UpdateEndpoint {

        @Test
        @DisplayName("updateSysUser - 更新成功")
        fun `updateSysUser should return success`() {
            val request = SysUserUpdateRequest(nickname = "New Nickname")
            `when`(sysUserService.updateUser(any(), any())).thenReturn(true)

            val result = controller.updateSysUser(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("updateSysUser - service 返回 false 时返回 error")
        fun `updateSysUser should return error when service returns false`() {
            val request = SysUserUpdateRequest(nickname = "New Nickname")
            `when`(sysUserService.updateUser(any(), any())).thenReturn(false)

            val result = controller.updateSysUser(1L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update user", result.message)
        }

        @Test
        @DisplayName("updateSysUser - 用户不存在时返回 error")
        fun `updateSysUser should return error when not found`() {
            val request = SysUserUpdateRequest(nickname = "New Nickname")
            `when`(sysUserService.updateUser(any(), any())).thenThrow(BizException("User not found"))

            val result = controller.updateSysUser(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("User not found", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/users/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggleSysUser - 切换状态成功")
        fun `toggleSysUser should return success`() {
            `when`(sysUserService.toggleUserStatus(1L, 0)).thenReturn(true)

            val result = controller.toggleSysUser(1L, 0)

            assertTrue(result.isSuccess())
            verify(sysUserService).toggleUserStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleSysUser - service 返回 false 时返回 error")
        fun `toggleSysUser should return error when service returns false`() {
            `when`(sysUserService.toggleUserStatus(1L, 1)).thenReturn(false)

            val result = controller.toggleSysUser(1L, 1)

            assertFalse(result.isSuccess())
            assertEquals("Failed to toggle user status", result.message)
        }

        @Test
        @DisplayName("toggleSysUser - service 抛异常返回 error")
        fun `toggleSysUser should return error when service throws`() {
            `when`(sysUserService.toggleUserStatus(999L, 1)).thenThrow(BizException("User not found"))

            val result = controller.toggleSysUser(999L, 1)

            assertFalse(result.isSuccess())
            assertEquals("User not found", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/users/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("deleteSysUser - 删除成功")
        fun `deleteSysUser should return success`() {
            `when`(sysUserService.deleteUser(1L)).thenReturn(true)

            val result = controller.deleteSysUser(1L)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("deleteSysUser - service 返回 false 时返回 error")
        fun `deleteSysUser should return error when service returns false`() {
            `when`(sysUserService.deleteUser(1L)).thenReturn(false)

            val result = controller.deleteSysUser(1L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete user", result.message)
        }

        @Test
        @DisplayName("deleteSysUser - 用户不存在时返回 error")
        fun `deleteSysUser should return error when not found`() {
            `when`(sysUserService.deleteUser(999L)).thenThrow(BizException("User not found"))

            val result = controller.deleteSysUser(999L)

            assertFalse(result.isSuccess())
            assertEquals("User not found", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/users/check/username")
    inner class CheckUsernameEndpoint {

        @Test
        @DisplayName("checkUsername - 已存在时返回 true")
        fun `checkUsername should return true when exists`() {
            `when`(sysUserService.existsByUsername("zhangsan")).thenReturn(true)

            val result = controller.checkUsername("zhangsan")

            assertTrue(result.isSuccess())
            assertEquals(true, result.data)
        }

        @Test
        @DisplayName("checkUsername - 不存在时返回 false")
        fun `checkUsername should return false when not exists`() {
            `when`(sysUserService.existsByUsername("nonexistent")).thenReturn(false)

            val result = controller.checkUsername("nonexistent")

            assertTrue(result.isSuccess())
            assertEquals(false, result.data)
        }

        @Test
        @DisplayName("checkUsername - service 抛异常返回 error")
        fun `checkUsername should return error when service throws`() {
            `when`(sysUserService.existsByUsername("zhangsan")).thenThrow(RuntimeException("DB error"))

            val result = controller.checkUsername("zhangsan")

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/users/check/phone")
    inner class CheckPhoneEndpoint {

        @Test
        @DisplayName("checkPhone - 已存在时返回 true")
        fun `checkPhone should return true when exists`() {
            `when`(sysUserService.existsByPhone("13800138000")).thenReturn(true)

            val result = controller.checkPhone("13800138000")

            assertTrue(result.isSuccess())
            assertEquals(true, result.data)
        }

        @Test
        @DisplayName("checkPhone - 不存在时返回 false")
        fun `checkPhone should return false when not exists`() {
            `when`(sysUserService.existsByPhone("13900000000")).thenReturn(false)

            val result = controller.checkPhone("13900000000")

            assertTrue(result.isSuccess())
            assertEquals(false, result.data)
        }

        @Test
        @DisplayName("checkPhone - service 抛异常返回 error")
        fun `checkPhone should return error when service throws`() {
            `when`(sysUserService.existsByPhone("13800138000")).thenThrow(RuntimeException("DB error"))

            val result = controller.checkPhone("13800138000")

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/users/check/email")
    inner class CheckEmailEndpoint {

        @Test
        @DisplayName("checkEmail - 已存在时返回 true")
        fun `checkEmail should return true when exists`() {
            `when`(sysUserService.existsByEmail("zhangsan@example.com")).thenReturn(true)

            val result = controller.checkEmail("zhangsan@example.com")

            assertTrue(result.isSuccess())
            assertEquals(true, result.data)
        }

        @Test
        @DisplayName("checkEmail - 不存在时返回 false")
        fun `checkEmail should return false when not exists`() {
            `when`(sysUserService.existsByEmail("nobody@example.com")).thenReturn(false)

            val result = controller.checkEmail("nobody@example.com")

            assertTrue(result.isSuccess())
            assertEquals(false, result.data)
        }

        @Test
        @DisplayName("checkEmail - service 抛异常返回 error")
        fun `checkEmail should return error when service throws`() {
            `when`(sysUserService.existsByEmail("zhangsan@example.com")).thenThrow(RuntimeException("DB error"))

            val result = controller.checkEmail("zhangsan@example.com")

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("Admin-only gate (AGENT-25)")
    inner class AdminOnlyGate {

        private fun assertRefused(call: () -> ResultVo<*>) {
            val result = call()
            assertFalse(result.isSuccess(), "a non-admin must not get a successful user read/write")
            assertEquals(403, result.code)
            assertEquals("error.user.admin_only", result.message)
            verifyNoInteractions(sysUserService)
        }

        @Test
        @DisplayName("pageSysUser - non-admin refused")
        fun `pageSysUser should be refused for a non-admin`() {
            mockLoggedInUser(memberUser)
            assertRefused { controller.pageSysUser(1, 10, null, null, null) }
        }

        @Test
        @DisplayName("getSysUser - non-admin refused")
        fun `getSysUser should be refused for a non-admin`() {
            mockLoggedInUser(memberUser)
            assertRefused { controller.getSysUser(1L) }
        }

        @Test
        @DisplayName("createUser - non-admin refused")
        fun `createUser should be refused for a non-admin`() {
            mockLoggedInUser(memberUser)
            val request = SysUserCreateRequest(username = "lisi", password = "password123", nickname = "Li Si")
            assertRefused { controller.createUser(request) }
        }

        @Test
        @DisplayName("updateSysUser - non-admin refused")
        fun `updateSysUser should be refused for a non-admin`() {
            mockLoggedInUser(memberUser)
            assertRefused { controller.updateSysUser(2L, SysUserUpdateRequest(nickname = "Self Promoted", isAdmin = 1)) }
        }

        @Test
        @DisplayName("toggleSysUser - non-admin refused")
        fun `toggleSysUser should be refused for a non-admin`() {
            mockLoggedInUser(memberUser)
            assertRefused { controller.toggleSysUser(2L, 0) }
        }

        @Test
        @DisplayName("deleteSysUser - non-admin refused")
        fun `deleteSysUser should be refused for a non-admin`() {
            mockLoggedInUser(memberUser)
            assertRefused { controller.deleteSysUser(3L) }
        }

        @Test
        @DisplayName("check endpoints - non-admin refused")
        fun `check endpoints should be refused for a non-admin`() {
            mockLoggedInUser(memberUser)
            assertRefused { controller.checkUsername("zhangsan") }
            assertRefused { controller.checkPhone("13800138000") }
            assertRefused { controller.checkEmail("zhangsan@example.com") }
        }

        @Test
        @DisplayName("pageSysUser - a principal with no user row is refused")
        fun `pageSysUser should be refused when the caller resolves to no user`() {
            // Fail closed: a bearer of the platform's internal secret authenticates as
            // `internal-service`, which is no SysUser at all.
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken("internal-service", null, emptyList())
            `when`(sysUserMapper.selectByUsername("internal-service")).thenReturn(null)
            assertRefused { controller.pageSysUser(1, 10, null, null, null) }
        }

        @Test
        @DisplayName("pageSysUser - the flag comes from the user row, not the caller's name")
        fun `gate should read the persisted flag rather than the principal`() {
            // Principal `admin` resolving to a non-admin row is the self-promotion shape: update copies
            // isAdmin verbatim, so an open write endpoint here would undo the read gate.
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken("admin", null, emptyList())
            `when`(sysUserMapper.selectByUsername("admin")).thenReturn(memberUser)
            assertRefused { controller.pageSysUser(1, 10, null, null, null) }
        }
    }
}
