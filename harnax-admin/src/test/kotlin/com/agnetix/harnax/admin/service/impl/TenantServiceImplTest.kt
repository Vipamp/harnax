package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.request.CreateTenantRequest
import com.agnetix.harnax.admin.entity.SysUser
import com.agnetix.harnax.admin.entity.TenantEntity
import com.agnetix.harnax.admin.entity.UserTenantEntity
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.mapper.SysUserMapper
import com.agnetix.harnax.admin.mapper.TenantMapper
import com.agnetix.harnax.admin.mapper.UserTenantMapper
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
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * TenantServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper layer dependencies
 *
 * @author agnetix
 * @since 2026-04-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
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

        // Mock messageUtil to return the key as message
        `when`(messageUtil.getMessage(anyString())).thenAnswer { it.arguments[0] as String }
        `when`(messageUtil.getMessage(anyString(), any())).thenAnswer { it.arguments[0] as String }
    }

    @Nested
    @DisplayName("Create Tenant Tests")
    inner class CreateTenantTests {

        @Test
        @DisplayName("createTenant - Create tenant successfully")
        fun `createTenant should create tenant successfully`() {
            // Given
            val request = CreateTenantRequest(
                name = "新租户",
                adminUserId = 1L,
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
        @DisplayName("createTenant - Throw exception when tenant name exists")
        fun `createTenant should throw exception when tenant name exists`() {
            // Given
            val request = CreateTenantRequest(
                name = "已存在租户",
                adminUserId = 1L,
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
        @DisplayName("createTenant - Throw exception when user not found")
        fun `createTenant should throw exception when user not found`() {
            // Given
            val request = CreateTenantRequest(
                name = "新租户",
                adminUserId = 999L,
            )

            `when`(tenantMapper.selectByName("新租户")).thenReturn(null)
            `when`(sysUserMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.createTenant(request, "admin")
            }
            assertEquals("error.user.notfound", exception.message)
            verify(tenantMapper).selectByName("新租户")
            verify(sysUserMapper).selectById(999L)
            verify(tenantMapper, never()).insert(any())
        }
    }

    @Nested
    @DisplayName("Get Tenant Tests")
    inner class GetTenantTests {

        @Test
        @DisplayName("getTenantById - Get tenant by ID")
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
        @DisplayName("getTenantById - Return null when tenant not found")
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
        @DisplayName("getTenantList - Get paginated tenant list")
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
            assertEquals(2, page.pageSize) // PageHelper returns actual record count
            assertEquals(2, page.records.size)
            assertEquals("测试租户", page.records[0].name)
            assertEquals("租户2", page.records[1].name)
            verify(tenantMapper).selectList(null, null)
        }

        @Test
        @DisplayName("getTenantList - Filter by name")
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
    @DisplayName("Toggle Tenant Status Tests")
    inner class ToggleStatusTests {

        @Test
        @DisplayName("toggleStatus - Disable active tenant")
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
        @DisplayName("toggleStatus - Enable disabled tenant")
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
        @DisplayName("toggleStatus - Throw exception when tenant not found")
        fun `toggleStatus should throw exception when tenant not found`() {
            // Given
            `when`(tenantMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.toggleStatus(999L)
            }
            assertEquals("error.tenant.notfound", exception.message)
            verify(tenantMapper, never()).updateStatus(any(), any())
        }
    }

    @Nested
    @DisplayName("Delete Tenant Tests")
    inner class DeleteTenantTests {

        @Test
        @DisplayName("deleteTenant - Delete tenant successfully")
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
        @DisplayName("deleteTenant - Throw exception when tenant not found")
        fun `deleteTenant should throw exception when tenant not found`() {
            // Given
            `when`(tenantMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.deleteTenant(999L)
            }
            assertEquals("error.tenant.notfound", exception.message)
            verify(tenantMapper).selectById(999L)
            verify(userTenantMapper, never()).deleteByTenantId(any())
            verify(tenantMapper, never()).deleteById(any())
        }
    }

    @Nested
    @DisplayName("Tenant User Management Tests")
    inner class TenantUserManagementTests {

        @Test
        @DisplayName("getTenantUsers - Get paginated tenant user list")
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
        @DisplayName("addUserToTenant - Add user to tenant successfully")
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
            verify(userTenantMapper).insert(
                argThat {
                    userId == 2L && tenantId == 1L && role == "member"
                },
            )
        }

        @Test
        @DisplayName("addUserToTenant - Throw exception when tenant not found")
        fun `addUserToTenant should throw exception when tenant not found`() {
            // Given
            `when`(tenantMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.addUserToTenant(999L, 1L, "member")
            }
            assertEquals("error.tenant.notfound", exception.message)
            verify(userTenantMapper, never()).insert(any())
        }

        @Test
        @DisplayName("addUserToTenant - Throw exception when user not found")
        fun `addUserToTenant should throw exception when user not found`() {
            // Given
            `when`(tenantMapper.selectById(1L)).thenReturn(testTenant)
            `when`(sysUserMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.addUserToTenant(1L, 999L, "member")
            }
            assertEquals("error.user.notfound", exception.message)
            verify(userTenantMapper, never()).insert(any())
        }

        @Test
        @DisplayName("addUserToTenant - Throw exception when user already in tenant")
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
            assertEquals("error.user.already_in_tenant", exception.message)
            verify(userTenantMapper, never()).insert(any())
        }

        @Test
        @DisplayName("removeUserFromTenant - Remove user from tenant successfully")
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
        @DisplayName("removeUserFromTenant - Throw BizException when user not in tenant")
        fun `removeUserFromTenant should throw exception when user not in tenant`() {
            // Given
            `when`(userTenantMapper.selectByUserIdAndTenantId(999L, 1L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.removeUserFromTenant(1L, 999L)
            }
            assertEquals("error.user.not_in_tenant", exception.message)
            verify(userTenantMapper, never()).deleteByUserIdAndTenantId(any(), any())
        }

        @Test
        @DisplayName("removeUserFromTenant - Throw BizException when removing only admin")
        fun `removeUserFromTenant should throw BizException when removing only admin`() {
            // Given
            val existingUserTenant = UserTenantEntity().apply {
                id = 1L
                userId = 2L
                tenantId = 1L
                role = "admin"
                status = 1
            }

            `when`(userTenantMapper.selectByUserIdAndTenantId(2L, 1L)).thenReturn(existingUserTenant)
            `when`(userTenantMapper.selectByTenantId(1L)).thenReturn(listOf(existingUserTenant))

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.removeUserFromTenant(1L, 2L)
            }
            assertEquals("error.tenant.cannot_remove_only_admin", exception.message)
            verify(userTenantMapper, never()).deleteByUserIdAndTenantId(any(), any())
        }

        @Test
        @DisplayName("removeUserFromTenant - Successfully remove admin when multiple admins exist")
        fun `removeUserFromTenant should successfully remove admin when multiple admins exist`() {
            // Given
            val userTenant1 = UserTenantEntity().apply {
                id = 1L
                userId = 2L
                tenantId = 1L
                role = "admin"
                status = 1
            }
            val userTenant2 = UserTenantEntity().apply {
                id = 2L
                userId = 3L
                tenantId = 1L
                role = "admin"
                status = 1
            }

            `when`(userTenantMapper.selectByUserIdAndTenantId(2L, 1L)).thenReturn(userTenant1)
            `when`(userTenantMapper.selectByTenantId(1L)).thenReturn(listOf(userTenant1, userTenant2))
            `when`(userTenantMapper.deleteByUserIdAndTenantId(2L, 1L)).thenReturn(1)

            // When
            val result = tenantService.removeUserFromTenant(1L, 2L)

            // Then
            assertTrue(result)
            verify(userTenantMapper).deleteByUserIdAndTenantId(2L, 1L)
        }
    }

    @Nested
    @DisplayName("Update User Role Tests")
    inner class UpdateUserRoleTests {

        @Test
        @DisplayName("updateUserRole - Successfully update user role")
        fun `updateUserRole should successfully update user role`() {
            // Given
            val existingUserTenant = UserTenantEntity().apply {
                id = 1L
                userId = 2L
                tenantId = 1L
                role = "member"
                status = 1
            }

            `when`(userTenantMapper.selectByUserIdAndTenantId(2L, 1L)).thenReturn(existingUserTenant)
            `when`(userTenantMapper.updateRole(2L, 1L, "admin")).thenReturn(1)

            // When
            val result = tenantService.updateUserRole(1L, 2L, "admin")

            // Then
            assertTrue(result)
            verify(userTenantMapper).updateRole(2L, 1L, "admin")
        }

        @Test
        @DisplayName("updateUserRole - Throw BizException when user not in tenant")
        fun `updateUserRole should throw BizException when user not in tenant`() {
            // Given
            `when`(userTenantMapper.selectByUserIdAndTenantId(999L, 1L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.updateUserRole(1L, 999L, "admin")
            }
            assertEquals("error.user.not_in_tenant", exception.message)
            verify(userTenantMapper, never()).updateRole(any(), any(), any())
        }

        @Test
        @DisplayName("updateUserRole - Throw BizException when demoting only admin")
        fun `updateUserRole should throw BizException when demoting only admin`() {
            // Given
            val existingUserTenant = UserTenantEntity().apply {
                id = 1L
                userId = 2L
                tenantId = 1L
                role = "admin"
                status = 1
            }

            `when`(userTenantMapper.selectByUserIdAndTenantId(2L, 1L)).thenReturn(existingUserTenant)
            `when`(userTenantMapper.selectByTenantId(1L)).thenReturn(listOf(existingUserTenant))

            // When & Then
            val exception = assertThrows<BizException> {
                tenantService.updateUserRole(1L, 2L, "member")
            }
            assertEquals("error.tenant.cannot_demote_only_admin", exception.message)
            verify(userTenantMapper, never()).updateRole(any(), any(), any())
        }

        @Test
        @DisplayName("updateUserRole - Successfully demote admin when multiple admins exist")
        fun `updateUserRole should successfully demote admin when multiple admins exist`() {
            // Given
            val userTenant1 = UserTenantEntity().apply {
                id = 1L
                userId = 2L
                tenantId = 1L
                role = "admin"
                status = 1
            }
            val userTenant2 = UserTenantEntity().apply {
                id = 2L
                userId = 3L
                tenantId = 1L
                role = "admin"
                status = 1
            }

            `when`(userTenantMapper.selectByUserIdAndTenantId(2L, 1L)).thenReturn(userTenant1)
            `when`(userTenantMapper.selectByTenantId(1L)).thenReturn(listOf(userTenant1, userTenant2))
            `when`(userTenantMapper.updateRole(2L, 1L, "member")).thenReturn(1)

            // When
            val result = tenantService.updateUserRole(1L, 2L, "member")

            // Then
            assertTrue(result)
            verify(userTenantMapper).updateRole(2L, 1L, "member")
        }
    }
}
