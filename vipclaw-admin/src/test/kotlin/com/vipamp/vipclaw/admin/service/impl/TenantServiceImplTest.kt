package com.vipamp.vipclaw.admin.service.impl

import com.github.pagehelper.PageInfo
import com.vipamp.vipclaw.admin.dto.request.CreateTenantRequest

import com.vipamp.vipclaw.admin.entity.SysUser
import com.vipamp.vipclaw.admin.entity.TenantEntity
import com.vipamp.vipclaw.admin.entity.UserTenantEntity
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.i18n.MessageUtil
import com.vipamp.vipclaw.admin.mapper.SysUserMapper
import com.vipamp.vipclaw.admin.mapper.TenantMapper
import com.vipamp.vipclaw.admin.mapper.UserTenantMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import java.time.LocalDateTime

/**
 * TenantServiceImpl 单元测试
 * 使用 Mockito 模拟 Mapper 层依赖
 *
 * @author vipamp
 * @since 2026-04-28
 */
@ExtendWith(MockitoExtension::class)
class TenantServiceImplTest {

    @Mock
    private lateinit var tenantMapper: TenantMapper

    @Mock
    private lateinit var userTenantMapper: UserTenantMapper

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @Mock
    private lateinit var messageUtil: MessageUtil

    @InjectMocks
    private lateinit var tenantService: TenantServiceImpl

    private lateinit var testTenant: TenantEntity
    private lateinit var testUser: SysUser

    @BeforeEach
    fun setUp() {
        testTenant = TenantEntity().apply {
            id = 1L
            name = "测试租户"
            status = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testUser = SysUser().apply {
            id = 1L
            username = "testuser"
            password = "hashed_password"
            nickname = "测试用户"
            email = "test@example.com"
            phone = "13800138000"
            status = 1
            isAdmin = 0
            active = 1
        }
    }

    @Nested
    @DisplayName("创建租户测试")
    inner class CreateTenantTests {

        @Test
        @DisplayName("createTenant - 正常创建租户")
        fun `createTenant should create tenant successfully`() {
            // Given
            val request = CreateTenantRequest(
                name = "新租户",
                adminUserId = 1L
            )
            val creator = "admin"

            `when`(tenantMapper.selectByName("新租户")).thenReturn(null)
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(tenantMapper.insert(any())).thenAnswer { invocation ->
                val tenant = invocation.getArgument<TenantEntity>(0)
                tenant.id = 2L
                1
            }
            `when`(userTenantMapper.insert(any())).thenReturn(1)

            // When
            val result = tenantService.createTenant(request, creator)

            // Then
            assertNotNull(result)
            assertEquals("新租户", result.name)
            assertEquals(1, result.status)
            verify(tenantMapper).selectByName("新租户")
            verify(sysUserMapper).selectById(1L)
            verify(tenantMapper).insert(argThat { name == "新租户" && creator == "admin" })
            verify(userTenantMapper).insert(argThat { userId == 1L && role == "admin" })
        }

        @Test
        @DisplayName("createTenant - 租户名称已存在时抛出异常")
        fun `createTenant should throw exception when tenant name exists`() {
            // Given
            val request = CreateTenantRequest(
                name = "已存在租户",
                adminUserId = 1L
            )

            `when`(tenantMapper.selectByName("已存在租户")).thenReturn(testTenant)
            `when`(messageUtil.getMessage("error.tenant.name_exists")).thenReturn("租户名称已存在")

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.createTenant(request, "admin")
            }
            assertEquals("租户名称已存在", exception.message)
            verify(tenantMapper).selectByName("已存在租户")
            verify(messageUtil).getMessage("error.tenant.name_exists")
            verifyNoInteractions(sysUserMapper)
        }

        @Test
        @DisplayName("createTenant - 用户不存在时抛出异常")
        fun `createTenant should throw exception when user not found`() {
            // Given
            val request = CreateTenantRequest(
                name = "新租户",
                adminUserId = 999L
            )

            `when`(tenantMapper.selectByName("新租户")).thenReturn(null)
            `when`(sysUserMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.createTenant(request, "admin")
            }
            assertEquals("用户不存在", exception.message)
            verify(tenantMapper).selectByName("新租户")
            verify(sysUserMapper).selectById(999L)
            verify(tenantMapper, never()).insert(any())
        }
    }

    @Nested
    @DisplayName("查询租户测试")
    inner class GetTenantTests {

        @Test
        @DisplayName("getTenantById - 根据ID查询租户")
        fun `getTenantById should return tenant response`() {
            // Given
            `when`(tenantMapper.selectById(1L)).thenReturn(testTenant)

            // When
            val result = tenantService.getTenantById(1L)

            // Then
            assertNotNull(result)
            assertEquals(1L, result!!.id)
            assertEquals("测试租户", result.name)
            assertEquals(1, result.status)
            verify(tenantMapper).selectById(1L)
        }

        @Test
        @DisplayName("getTenantById - 租户不存在时返回null")
        fun `getTenantById should return null when tenant not found`() {
            // Given
            `when`(tenantMapper.selectById(999L)).thenReturn(null)

            // When
            val result = tenantService.getTenantById(999L)

            // Then
            assertNull(result)
            verify(tenantMapper).selectById(999L)
        }

        @Test
        @DisplayName("getTenantList - 分页查询租户列表")
        fun `getTenantList should return paginated results`() {
            // Given
            val tenant2 = TenantEntity().apply {
                id = 2L
                name = "租户2"
                status = 1
                creator = "admin"
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }
            val tenants = listOf(testTenant, tenant2)

            `when`(tenantMapper.selectList(null, null)).thenReturn(tenants)

            // When
            val page = tenantService.getTenantList(null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertEquals(2, page.total)
            assertEquals(1, page.pageNum)
            assertEquals(2, page.pageSize) // PageHelper 返回实际记录数
            assertEquals(2, page.records.size)
            assertEquals("测试租户", page.records[0].name)
            assertEquals("租户2", page.records[1].name)
            verify(tenantMapper).selectList(null, null)
        }

        @Test
        @DisplayName("getTenantList - 按名称筛选")
        fun `getTenantList should filter by name`() {
            // Given
            val tenants = listOf(testTenant)

            `when`(tenantMapper.selectList("测试", null)).thenReturn(tenants)

            // When
            val page = tenantService.getTenantList("测试", null, 1, 10)

            // Then
            assertNotNull(page)
            assertEquals(1, page.total)
            assertEquals("测试租户", page.records[0].name)
            verify(tenantMapper).selectList("测试", null)
        }
    }



    @Nested
    @DisplayName("切换租户状态测试")
    inner class ToggleStatusTests {

        @Test
        @DisplayName("toggleStatus - 从启用切换到禁用")
        fun `toggleStatus should disable active tenant`() {
            // Given
            `when`(tenantMapper.selectById(1L)).thenReturn(testTenant)
            `when`(tenantMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = tenantService.toggleStatus(1L)

            // Then
            assertTrue(result)
            verify(tenantMapper).selectById(1L)
            verify(tenantMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleStatus - 从禁用切换到启用")
        fun `toggleStatus should enable disabled tenant`() {
            // Given
            val disabledTenant = TenantEntity().apply {
                id = 2L
                name = "禁用租户"
                status = 0
                creator = "admin"
                active = 1
            }

            `when`(tenantMapper.selectById(2L)).thenReturn(disabledTenant)
            `when`(tenantMapper.updateStatus(2L, 1)).thenReturn(1)

            // When
            val result = tenantService.toggleStatus(2L)

            // Then
            assertTrue(result)
            verify(tenantMapper).selectById(2L)
            verify(tenantMapper).updateStatus(2L, 1)
        }

        @Test
        @DisplayName("toggleStatus - 租户不存在时抛出异常")
        fun `toggleStatus should throw exception when tenant not found`() {
            // Given
            `when`(tenantMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.toggleStatus(999L)
            }
            assertEquals("租户不存在", exception.message)
            verify(tenantMapper, never()).updateStatus(any(), any())
        }
    }

    @Nested
    @DisplayName("删除租户测试")
    inner class DeleteTenantTests {

        @Test
        @DisplayName("deleteTenant - 正常删除租户")
        fun `deleteTenant should delete tenant successfully`() {
            // Given
            `when`(tenantMapper.selectById(1L)).thenReturn(testTenant)
            `when`(userTenantMapper.deleteByTenantId(1L)).thenReturn(1)
            `when`(tenantMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = tenantService.deleteTenant(1L)

            // Then
            assertTrue(result)
            verify(tenantMapper).selectById(1L)
            verify(userTenantMapper).deleteByTenantId(1L)
            verify(tenantMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteTenant - 租户不存在时抛出异常")
        fun `deleteTenant should throw exception when tenant not found`() {
            // Given
            `when`(tenantMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.deleteTenant(999L)
            }
            assertEquals("租户不存在", exception.message)
            verify(tenantMapper).selectById(999L)
            verify(userTenantMapper, never()).deleteByTenantId(any())
            verify(tenantMapper, never()).deleteById(any())
        }
    }

    @Nested
    @DisplayName("租户用户管理测试")
    inner class TenantUserManagementTests {

        @Test
        @DisplayName("getTenantUsers - 分页查询租户用户列表")
        fun `getTenantUsers should return paginated user list`() {
            // Given
            val userTenant1 = UserTenantEntity().apply {
                id = 1L
                userId = 1L
                tenantId = 1L
                role = "admin"
                status = 1
                joinedAt = LocalDateTime.now()
            }
            val userTenant2 = UserTenantEntity().apply {
                id = 2L
                userId = 2L
                tenantId = 1L
                role = "member"
                status = 1
                joinedAt = LocalDateTime.now()
            }
            val userTenants = listOf(userTenant1, userTenant2)

            val user2 = SysUser().apply {
                id = 2L
                username = "user2"
                nickname = "用户2"
            }

            `when`(userTenantMapper.selectByTenantId(1L)).thenReturn(userTenants)
            `when`(sysUserMapper.selectById(1L)).thenReturn(testUser)
            `when`(sysUserMapper.selectById(2L)).thenReturn(user2)

            // When
            val page = tenantService.getTenantUsers(1L, 1, 10)

            // Then
            assertNotNull(page)
            assertEquals(2, page.total)
            assertEquals(2, page.records.size)
            assertEquals("testuser", page.records[0].username)
            assertEquals("admin", page.records[0].role)
            assertEquals("user2", page.records[1].username)
            assertEquals("member", page.records[1].role)
            verify(userTenantMapper).selectByTenantId(1L)
        }

        @Test
        @DisplayName("addUserToTenant - 正常添加用户到租户")
        fun `addUserToTenant should add user to tenant successfully`() {
            // Given
            `when`(tenantMapper.selectById(1L)).thenReturn(testTenant)
            `when`(sysUserMapper.selectById(2L)).thenReturn(testUser.apply { id = 2L })
            `when`(userTenantMapper.selectByUserIdAndTenantId(2L, 1L)).thenReturn(null)
            `when`(userTenantMapper.insert(any())).thenReturn(1)

            // When
            val result = tenantService.addUserToTenant(1L, 2L, "member")

            // Then
            assertTrue(result)
            verify(tenantMapper).selectById(1L)
            verify(sysUserMapper).selectById(2L)
            verify(userTenantMapper).selectByUserIdAndTenantId(2L, 1L)
            verify(userTenantMapper).insert(argThat {
                userId == 2L && tenantId == 1L && role == "member"
            })
        }

        @Test
        @DisplayName("addUserToTenant - 租户不存在时抛出异常")
        fun `addUserToTenant should throw exception when tenant not found`() {
            // Given
            `when`(tenantMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.addUserToTenant(999L, 1L, "member")
            }
            assertEquals("租户不存在", exception.message)
            verify(userTenantMapper, never()).insert(any())
        }

        @Test
        @DisplayName("addUserToTenant - 用户不存在时抛出异常")
        fun `addUserToTenant should throw exception when user not found`() {
            // Given
            `when`(tenantMapper.selectById(1L)).thenReturn(testTenant)
            `when`(sysUserMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.addUserToTenant(1L, 999L, "member")
            }
            assertEquals("用户不存在", exception.message)
            verify(userTenantMapper, never()).insert(any())
        }

        @Test
        @DisplayName("addUserToTenant - 用户已在租户中时抛出异常")
        fun `addUserToTenant should throw exception when user already in tenant`() {
            // Given
            val existingUserTenant = UserTenantEntity().apply {
                id = 1L
                userId = 2L
                tenantId = 1L
                role = "member"
                status = 1
            }

            `when`(tenantMapper.selectById(1L)).thenReturn(testTenant)
            `when`(sysUserMapper.selectById(2L)).thenReturn(testUser.apply { id = 2L })
            `when`(userTenantMapper.selectByUserIdAndTenantId(2L, 1L)).thenReturn(existingUserTenant)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.addUserToTenant(1L, 2L, "member")
            }
            assertEquals("用户已在该租户中", exception.message)
            verify(userTenantMapper, never()).insert(any())
        }

        @Test
        @DisplayName("removeUserFromTenant - 正常从租户移除用户")
        fun `removeUserFromTenant should remove user from tenant successfully`() {
            // Given
            val existingUserTenant = UserTenantEntity().apply {
                id = 1L
                userId = 2L
                tenantId = 1L
                role = "member"
                status = 1
            }

            `when`(userTenantMapper.selectByUserIdAndTenantId(2L, 1L)).thenReturn(existingUserTenant)
            `when`(userTenantMapper.deleteByUserIdAndTenantId(2L, 1L)).thenReturn(1)

            // When
            val result = tenantService.removeUserFromTenant(1L, 2L)

            // Then
            assertTrue(result)
            verify(userTenantMapper).selectByUserIdAndTenantId(2L, 1L)
            verify(userTenantMapper).deleteByUserIdAndTenantId(2L, 1L)
        }

        @Test
        @DisplayName("removeUserFromTenant - 用户不在租户中时抛出异常")
        fun `removeUserFromTenant should throw exception when user not in tenant`() {
            // Given
            `when`(userTenantMapper.selectByUserIdAndTenantId(999L, 1L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.removeUserFromTenant(1L, 999L)
            }
            assertEquals("用户不在该租户中", exception.message)
            verify(userTenantMapper, never()).deleteByUserIdAndTenantId(any(), any())
        }
    }
}
