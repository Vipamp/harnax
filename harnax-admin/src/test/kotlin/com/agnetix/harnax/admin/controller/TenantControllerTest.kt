package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.request.AddUserToTenantRequest
import com.agnetix.harnax.admin.dto.request.CreateTenantRequest
import com.agnetix.harnax.admin.dto.response.TenantResponse
import com.agnetix.harnax.admin.dto.response.UserTenantResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.TenantService
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
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * TenantController 单元测试
 * 直接实例化 Controller + Mock Service，断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantControllerTest {

    @Mock
    private lateinit var tenantService: TenantService

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @InjectMocks
    private lateinit var controller: TenantController

    private lateinit var adminUser: SysUser
    private lateinit var normalUser: SysUser
    private lateinit var testTenant: TenantResponse

    @BeforeEach
    fun setUp() {
        // 模拟当前请求上下文（UserContextUtil 依赖 RequestContextHolder）
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))

        `when`(jwtUtil.validateToken(any())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(any())).thenReturn("admin")

        adminUser = SysUser().apply {
            id = 1L
            username = "admin"
            nickname = "Administrator"
            isAdmin = 1
        }
        normalUser = SysUser().apply {
            id = 2L
            username = "admin"
            nickname = "Normal User"
            isAdmin = 0
        }
        testTenant = TenantResponse(id = 1L, name = "Example Company", status = 1, creator = "admin")
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Nested
    @DisplayName("POST /api/admin/tenant")
    inner class CreateEndpoint {

        @Test
        @DisplayName("createTenant - 全局管理员创建租户成功")
        fun `createTenant should return tenant when admin`() {
            `when`(sysUserMapper.selectByUsername("admin")).thenReturn(adminUser)
            `when`(tenantService.createTenant(any(), any())).thenReturn(testTenant)

            val result = controller.createTenant(CreateTenantRequest(name = "Example Company", adminUserId = 1L))

            assertTrue(result.isSuccess())
            assertEquals("Example Company", result.data?.name)
        }

        @Test
        @DisplayName("createTenant - 用户不存在时返回 error")
        fun `createTenant should return error when user not found`() {
            `when`(sysUserMapper.selectByUsername("admin")).thenReturn(null)

            val result = controller.createTenant(CreateTenantRequest(name = "Example Company", adminUserId = 1L))

            assertFalse(result.isSuccess())
            assertEquals("User not found", result.message)
        }

        @Test
        @DisplayName("createTenant - 非全局管理员时返回 error")
        fun `createTenant should return error when not admin`() {
            `when`(sysUserMapper.selectByUsername("admin")).thenReturn(normalUser)

            val result = controller.createTenant(CreateTenantRequest(name = "Example Company", adminUserId = 1L))

            assertFalse(result.isSuccess())
            assertEquals("Only global admin can create tenants", result.message)
        }

        @Test
        @DisplayName("createTenant - 未登录时返回 error")
        fun `createTenant should return error when not logged in`() {
            `when`(jwtUtil.validateToken(any())).thenReturn(false)

            val result = controller.createTenant(CreateTenantRequest(name = "Example Company", adminUserId = 1L))

            assertFalse(result.isSuccess())
            assertEquals("Not logged in", result.message)
        }

        @Test
        @DisplayName("createTenant - service 抛异常返回 error")
        fun `createTenant should return error when service throws`() {
            `when`(sysUserMapper.selectByUsername("admin")).thenReturn(adminUser)
            `when`(tenantService.createTenant(any(), any())).thenThrow(BizException("Tenant name already exists"))

            val result = controller.createTenant(CreateTenantRequest(name = "Example Company", adminUserId = 1L))

            assertFalse(result.isSuccess())
            assertEquals("Tenant name already exists", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/tenant/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getTenant - 返回租户详情")
        fun `getTenant should return tenant details`() {
            `when`(tenantService.getTenantById(1L)).thenReturn(testTenant)

            val result = controller.getTenant(1L)

            assertTrue(result.isSuccess())
            assertEquals("Example Company", result.data?.name)
        }

        @Test
        @DisplayName("getTenant - 不存在时返回 error")
        fun `getTenant should return error when not found`() {
            `when`(tenantService.getTenantById(999L)).thenReturn(null)

            val result = controller.getTenant(999L)

            assertFalse(result.isSuccess())
            assertEquals("Tenant not found", result.message)
        }

        @Test
        @DisplayName("getTenant - service 抛异常返回 error")
        fun `getTenant should return error when service throws`() {
            `when`(tenantService.getTenantById(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.getTenant(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/tenant")
    inner class ListEndpoint {

        @Test
        @DisplayName("getTenantList - 返回分页结果")
        fun `getTenantList should return paginated results`() {
            val page = Page<TenantResponse>(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testTenant))
            `when`(tenantService.getTenantList(null, null, 1, 10)).thenReturn(page)

            val result = controller.getTenantList(1, 10, null, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
        }

        @Test
        @DisplayName("getTenantList - 传递过滤条件")
        fun `getTenantList should pass filters correctly`() {
            val page = Page<TenantResponse>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(tenantService.getTenantList("Example", 1, 1, 10)).thenReturn(page)

            val result = controller.getTenantList(1, 10, "Example", 1)

            assertTrue(result.isSuccess())
            verify(tenantService).getTenantList("Example", 1, 1, 10)
        }

        @Test
        @DisplayName("getTenantList - service 抛异常返回 error")
        fun `getTenantList should return error when service throws`() {
            `when`(tenantService.getTenantList(null, null, 1, 10)).thenThrow(RuntimeException("DB error"))

            val result = controller.getTenantList(1, 10, null, null)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/tenant/{id}/status")
    inner class ToggleStatusEndpoint {

        @Test
        @DisplayName("toggleStatus - 切换状态成功")
        fun `toggleStatus should return success`() {
            `when`(tenantService.toggleStatus(1L)).thenReturn(true)

            val result = controller.toggleStatus(1L)

            assertTrue(result.isSuccess())
            assertEquals(true, result.data)
        }

        @Test
        @DisplayName("toggleStatus - service 抛异常返回 error")
        fun `toggleStatus should return error when service throws`() {
            `when`(tenantService.toggleStatus(999L)).thenThrow(BizException("Tenant not found"))

            val result = controller.toggleStatus(999L)

            assertFalse(result.isSuccess())
            assertEquals("Tenant not found", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/tenant/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("deleteTenant - 全局管理员删除成功")
        fun `deleteTenant should return success when admin`() {
            `when`(sysUserMapper.selectByUsername("admin")).thenReturn(adminUser)
            `when`(tenantService.deleteTenant(1L)).thenReturn(true)

            val result = controller.deleteTenant(1L)

            assertTrue(result.isSuccess())
            assertEquals(true, result.data)
        }

        @Test
        @DisplayName("deleteTenant - 用户不存在时返回 error")
        fun `deleteTenant should return error when user not found`() {
            `when`(sysUserMapper.selectByUsername("admin")).thenReturn(null)

            val result = controller.deleteTenant(1L)

            assertFalse(result.isSuccess())
            assertEquals("User not found", result.message)
        }

        @Test
        @DisplayName("deleteTenant - 非全局管理员时返回 error")
        fun `deleteTenant should return error when not admin`() {
            `when`(sysUserMapper.selectByUsername("admin")).thenReturn(normalUser)

            val result = controller.deleteTenant(1L)

            assertFalse(result.isSuccess())
            assertEquals("Only global admin can delete tenants", result.message)
        }

        @Test
        @DisplayName("deleteTenant - service 抛异常返回 error")
        fun `deleteTenant should return error when service throws`() {
            `when`(sysUserMapper.selectByUsername("admin")).thenReturn(adminUser)
            `when`(tenantService.deleteTenant(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.deleteTenant(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/tenant/{id}/users")
    inner class TenantUsersEndpoint {

        @Test
        @DisplayName("getTenantUsers - 返回分页用户列表")
        fun `getTenantUsers should return paginated users`() {
            val page = Page<UserTenantResponse>(
                total = 1L,
                pageNum = 1L,
                pageSize = 10L,
                records = listOf(UserTenantResponse(id = 1L, userId = 2L, username = "zhangsan", role = "member")),
            )
            `when`(tenantService.getTenantUsers(1L, 1, 10)).thenReturn(page)

            val result = controller.getTenantUsers(1L, 1, 10)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("zhangsan", result.data?.records?.get(0)?.username)
        }

        @Test
        @DisplayName("getTenantUsers - service 抛异常返回 error")
        fun `getTenantUsers should return error when service throws`() {
            `when`(tenantService.getTenantUsers(1L, 1, 10)).thenThrow(RuntimeException("DB error"))

            val result = controller.getTenantUsers(1L, 1, 10)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/tenant/{id}/users")
    inner class AddUserEndpoint {

        @Test
        @DisplayName("addUserToTenant - 添加成功")
        fun `addUserToTenant should return success`() {
            `when`(tenantService.addUserToTenant(1L, 2L, "member")).thenReturn(true)

            val result = controller.addUserToTenant(1L, AddUserToTenantRequest(userId = 2L, role = "member"))

            assertTrue(result.isSuccess())
            assertEquals(true, result.data)
        }

        @Test
        @DisplayName("addUserToTenant - 用户已在租户时返回 error")
        fun `addUserToTenant should return error when user already in tenant`() {
            `when`(tenantService.addUserToTenant(1L, 2L, "member"))
                .thenThrow(BizException("User already in tenant"))

            val result = controller.addUserToTenant(1L, AddUserToTenantRequest(userId = 2L, role = "member"))

            assertFalse(result.isSuccess())
            assertEquals("User already in tenant", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/tenant/{id}/users/{userId}")
    inner class RemoveUserEndpoint {

        @Test
        @DisplayName("removeUserFromTenant - 移除成功")
        fun `removeUserFromTenant should return success`() {
            `when`(tenantService.removeUserFromTenant(1L, 2L)).thenReturn(true)

            val result = controller.removeUserFromTenant(1L, 2L)

            assertTrue(result.isSuccess())
            assertEquals(true, result.data)
        }

        @Test
        @DisplayName("removeUserFromTenant - service 抛异常返回 error")
        fun `removeUserFromTenant should return error when service throws`() {
            `when`(tenantService.removeUserFromTenant(1L, 999L))
                .thenThrow(BizException("User not in tenant"))

            val result = controller.removeUserFromTenant(1L, 999L)

            assertFalse(result.isSuccess())
            assertEquals("User not in tenant", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/tenant/{id}/users/{userId}/role")
    inner class UpdateUserRoleEndpoint {

        @Test
        @DisplayName("updateUserRole - 更新角色成功")
        fun `updateUserRole should return success`() {
            `when`(tenantService.updateUserRole(1L, 2L, "admin")).thenReturn(true)

            val result = controller.updateUserRole(1L, 2L, TenantController.UpdateUserRoleRequest(role = "admin"))

            assertTrue(result.isSuccess())
            assertEquals(true, result.data)
        }

        @Test
        @DisplayName("updateUserRole - service 抛异常返回 error")
        fun `updateUserRole should return error when service throws`() {
            `when`(tenantService.updateUserRole(1L, 999L, "admin"))
                .thenThrow(BizException("User not in tenant"))

            val result = controller.updateUserRole(1L, 999L, TenantController.UpdateUserRoleRequest(role = "admin"))

            assertFalse(result.isSuccess())
            assertEquals("User not in tenant", result.message)
        }
    }
}
