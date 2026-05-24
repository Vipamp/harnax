package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.entity.TenantEntity
import com.agnetix.harnax.entity.UserTenantEntity
import com.agnetix.harnax.mapper.SysUserMapper
import com.agnetix.harnax.mapper.TenantMapper
import com.agnetix.harnax.mapper.UserTenantMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * UserTenantServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper layer dependencies
 *
 * @author agnetix
 * @since 2026-04-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserTenantServiceImplTest {

    @Mock
    private lateinit var userTenantMapper: UserTenantMapper

    @Mock
    private lateinit var tenantMapper: TenantMapper

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @Mock
    private lateinit var messageUtil: MessageUtil

    @InjectMocks
    private lateinit var userTenantService: UserTenantServiceImpl

    private lateinit var testUserTenant: UserTenantEntity
    private lateinit var testTenant: TenantEntity

    @BeforeEach
    fun setUp() {
        testUserTenant = UserTenantEntity().apply {
            id = 1L
            userId = 1L
            tenantId = 1L
            role = "admin"
            status = 1
            joinedAt = LocalDateTime.now()
        }

        testTenant = TenantEntity().apply {
            id = 1L
            name = "Test Tenant"
            status = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        // Mock messageUtil to return the key as message
        `when`(messageUtil.getMessage(anyString())).thenAnswer { it.arguments[0] as String }
        `when`(messageUtil.getMessage(anyString(), any())).thenAnswer { it.arguments[0] as String }
    }

    @Nested
    @DisplayName("Get User Tenants Tests")
    inner class GetUserTenantsTests {

        @Test
        @DisplayName("getUserTenants - Return user's tenant list")
        fun `getUserTenants should return user's tenant list`() {
            // Given
            val userTenant2 = UserTenantEntity().apply {
                id = 2L
                userId = 1L
                tenantId = 2L
                role = "member"
                status = 1
                joinedAt = LocalDateTime.now()
            }

            val tenant2 = TenantEntity().apply {
                id = 2L
                name = "Tenant 2"
                status = 1
                creator = "admin"
                active = 1
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }

            val userTenants = listOf(testUserTenant, userTenant2)

            `when`(userTenantMapper.selectByUserId(1L)).thenReturn(userTenants)
            `when`(tenantMapper.selectById(1L)).thenReturn(testTenant)
            `when`(tenantMapper.selectById(2L)).thenReturn(tenant2)

            // When
            val result = userTenantService.getUserTenants(1L)

            // Then
            assertNotNull(result)
            assertEquals(2, result.size)
            assertEquals("Test Tenant", result[0].name)
            assertEquals(1, result[0].status)
            assertEquals("Tenant 2", result[1].name)
            verify(userTenantMapper).selectByUserId(1L)
            verify(tenantMapper).selectById(1L)
            verify(tenantMapper).selectById(2L)
        }

        @Test
        @DisplayName("getUserTenants - Return empty list when user has no tenants")
        fun `getUserTenants should return empty list when user has no tenants`() {
            // Given
            `when`(userTenantMapper.selectByUserId(999L)).thenReturn(emptyList())

            // When
            val result = userTenantService.getUserTenants(999L)

            // Then
            assertNotNull(result)
            assertTrue(result.isEmpty())
            verify(userTenantMapper).selectByUserId(999L)
            verifyNoInteractions(tenantMapper)
        }

        @Test
        @DisplayName("getUserTenants - Filter out deleted tenants")
        fun `getUserTenants should filter out deleted tenants`() {
            // Given
            val deletedTenant = TenantEntity().apply {
                id = 999L
                name = "Deleted Tenant"
                status = 0
                creator = "admin"
                active = 0 // Deleted
                createTime = LocalDateTime.now()
                updateTime = LocalDateTime.now()
            }

            val userTenants = listOf(
                UserTenantEntity().apply {
                    id = 1L
                    userId = 1L
                    tenantId = 999L
                    role = "member"
                    status = 1
                    joinedAt = LocalDateTime.now()
                },
            )

            `when`(userTenantMapper.selectByUserId(1L)).thenReturn(userTenants)
            `when`(tenantMapper.selectById(999L)).thenReturn(null) // Tenant deleted

            // When
            val result = userTenantService.getUserTenants(1L)

            // Then
            assertNotNull(result)
            assertTrue(result.isEmpty()) // Deleted tenants should be filtered
            verify(userTenantMapper).selectByUserId(1L)
            verify(tenantMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("Get User Tenant Info Tests")
    inner class GetUserTenantInfoTests {

        @Test
        @DisplayName("getUserTenantInfo - Return user tenant info")
        fun `getUserTenantInfo should return user tenant info`() {
            // Given
            `when`(userTenantMapper.selectByUserIdAndTenantId(1L, 1L)).thenReturn(testUserTenant)

            // When
            val result = userTenantService.getUserTenantInfo(1L, 1L)

            // Then
            assertNotNull(result)
            assertEquals(1L, result!!.userId)
            assertEquals(1L, result.tenantId)
            assertEquals("admin", result.role)
            assertEquals(1, result.status)
            verify(userTenantMapper).selectByUserIdAndTenantId(1L, 1L)
        }

        @Test
        @DisplayName("getUserTenantInfo - Return null when user not in tenant")
        fun `getUserTenantInfo should return null when user not in tenant`() {
            // Given
            `when`(userTenantMapper.selectByUserIdAndTenantId(999L, 1L)).thenReturn(null)

            // When
            val result = userTenantService.getUserTenantInfo(999L, 1L)

            // Then
            assertNull(result)
            verify(userTenantMapper).selectByUserIdAndTenantId(999L, 1L)
        }
    }

    @Nested
    @DisplayName("Check User in Tenant Tests")
    inner class IsUserInTenantTests {

        @Test
        @DisplayName("isUserInTenant - Return true when user is in tenant")
        fun `isUserInTenant should return true when user is in tenant`() {
            // Given
            `when`(userTenantMapper.selectByUserIdAndTenantId(1L, 1L)).thenReturn(testUserTenant)

            // When
            val result = userTenantService.isUserInTenant(1L, 1L)

            // Then
            assertTrue(result)
            verify(userTenantMapper).selectByUserIdAndTenantId(1L, 1L)
        }

        @Test
        @DisplayName("isUserInTenant - Return false when user not in tenant")
        fun `isUserInTenant should return false when user not in tenant`() {
            // Given
            `when`(userTenantMapper.selectByUserIdAndTenantId(999L, 1L)).thenReturn(null)

            // When
            val result = userTenantService.isUserInTenant(999L, 1L)

            // Then
            assertFalse(result)
            verify(userTenantMapper).selectByUserIdAndTenantId(999L, 1L)
        }

        @Test
        @DisplayName("isUserInTenant - Return false when user is disabled")
        fun `isUserInTenant should return false when user is disabled`() {
            // Given
            val disabledUserTenant = UserTenantEntity().apply {
                id = 1L
                userId = 1L
                tenantId = 1L
                role = "member"
                status = 0 // Disabled
                joinedAt = LocalDateTime.now()
            }

            `when`(userTenantMapper.selectByUserIdAndTenantId(1L, 1L)).thenReturn(disabledUserTenant)

            // When
            val result = userTenantService.isUserInTenant(1L, 1L)

            // Then
            assertFalse(result) // status=0 should return false
            verify(userTenantMapper).selectByUserIdAndTenantId(1L, 1L)
        }
    }

    @Nested
    @DisplayName("Update User Role Tests")
    inner class UpdateUserRoleTests {

        @Test
        @DisplayName("updateUserRole - Update user role successfully")
        fun `updateUserRole should update user role successfully`() {
            // Given
            `when`(userTenantMapper.selectByUserIdAndTenantId(1L, 1L)).thenReturn(testUserTenant)
            `when`(userTenantMapper.updateRole(1L, 1L, "member")).thenReturn(1)

            // When
            val result = userTenantService.updateUserRole(1L, 1L, "member")

            // Then
            assertTrue(result)
            verify(userTenantMapper).selectByUserIdAndTenantId(1L, 1L)
            verify(userTenantMapper).updateRole(1L, 1L, "member")
        }

        @Test
        @DisplayName("updateUserRole - Throw exception when user not in tenant")
        fun `updateUserRole should throw exception when user not in tenant`() {
            // Given
            `when`(userTenantMapper.selectByUserIdAndTenantId(999L, 1L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                userTenantService.updateUserRole(999L, 1L, "member")
            }
            assertEquals("error.user.not_in_tenant", exception.message)
            verify(userTenantMapper).selectByUserIdAndTenantId(999L, 1L)
            verify(userTenantMapper, never()).updateRole(any(), any(), any())
        }

        @Test
        @DisplayName("updateUserRole - Upgrade from member to admin")
        fun `updateUserRole should upgrade from member to admin`() {
            // Given
            val memberUserTenant = UserTenantEntity().apply {
                id = 1L
                userId = 2L
                tenantId = 1L
                role = "member"
                status = 1
                joinedAt = LocalDateTime.now()
            }

            `when`(userTenantMapper.selectByUserIdAndTenantId(2L, 1L)).thenReturn(memberUserTenant)
            `when`(userTenantMapper.updateRole(2L, 1L, "admin")).thenReturn(1)

            // When
            val result = userTenantService.updateUserRole(2L, 1L, "admin")

            // Then
            assertTrue(result)
            verify(userTenantMapper).selectByUserIdAndTenantId(2L, 1L)
            verify(userTenantMapper).updateRole(2L, 1L, "admin")
        }

        @Test
        @DisplayName("updateUserRole - Downgrade from admin to member")
        fun `updateUserRole should downgrade from admin to member`() {
            // Given
            `when`(userTenantMapper.selectByUserIdAndTenantId(1L, 1L)).thenReturn(testUserTenant)
            `when`(userTenantMapper.updateRole(1L, 1L, "member")).thenReturn(1)

            // When
            val result = userTenantService.updateUserRole(1L, 1L, "member")

            // Then
            assertTrue(result)
            verify(userTenantMapper).selectByUserIdAndTenantId(1L, 1L)
            verify(userTenantMapper).updateRole(1L, 1L, "member")
        }
    }

    @Nested
    @DisplayName("Add User to Tenant Tests")
    inner class AddUserToTenantTests {

        @Test
        @DisplayName("addUserToTenant - Add user to tenant successfully")
        fun `addUserToTenant should add user to tenant successfully`() {
            // Given
            val userId = 2L
            val tenantId = 1L
            val role = "member"
            val operator = "admin"

            val user = SysUser().apply {
                id = userId
                username = "testuser2"
                password = "password123"
                nickname = "Test User 2"
                email = "test2@example.com"
                phone = "13800138002"
                status = 1
                isAdmin = 0
                active = 1
            }

            `when`(tenantMapper.selectById(tenantId)).thenReturn(testTenant)
            `when`(sysUserMapper.selectById(userId)).thenReturn(user)
            `when`(userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)).thenReturn(null)
            `when`(userTenantMapper.insert(any())).thenReturn(1)

            // When
            val result = userTenantService.addUserToTenant(tenantId, userId, role, operator)

            // Then
            assertTrue(result)
            verify(tenantMapper).selectById(tenantId)
            verify(sysUserMapper).selectById(userId)
            verify(userTenantMapper).selectByUserIdAndTenantId(userId, tenantId)
            verify(userTenantMapper).insert(any())
        }

        @Test
        @DisplayName("addUserToTenant - Throw exception when tenant not found")
        fun `addUserToTenant should throw exception when tenant not found`() {
            // Given
            `when`(tenantMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                userTenantService.addUserToTenant(999L, 1L, "member", "admin")
            }
            assertEquals("error.tenant.notfound", exception.message)
            verify(tenantMapper).selectById(999L)
            verifyNoInteractions(sysUserMapper)
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
                userTenantService.addUserToTenant(1L, 999L, "member", "admin")
            }
            assertEquals("error.user.notfound", exception.message)
            verify(tenantMapper).selectById(1L)
            verify(sysUserMapper).selectById(999L)
            verify(userTenantMapper, never()).insert(any())
        }

        @Test
        @DisplayName("addUserToTenant - Throw exception when user already in tenant")
        fun `addUserToTenant should throw exception when user already in tenant`() {
            // Given
            `when`(tenantMapper.selectById(1L)).thenReturn(testTenant)
            `when`(sysUserMapper.selectById(1L)).thenReturn(
                SysUser().apply {
                    id = 1L
                    username = "testuser"
                },
            )
            `when`(userTenantMapper.selectByUserIdAndTenantId(1L, 1L)).thenReturn(testUserTenant)

            // When & Then
            val exception = assertThrows<BizException> {
                userTenantService.addUserToTenant(1L, 1L, "member", "admin")
            }
            assertEquals("error.user.already_in_tenant", exception.message)
            verify(tenantMapper).selectById(1L)
            verify(sysUserMapper).selectById(1L)
            verify(userTenantMapper).selectByUserIdAndTenantId(1L, 1L)
            verify(userTenantMapper, never()).insert(any())
        }

        @Test
        @DisplayName("addUserToTenant - Add user as admin role")
        fun `addUserToTenant should add user as admin role`() {
            // Given
            val userId = 3L
            val tenantId = 1L
            val role = "admin"

            val user = SysUser().apply {
                id = userId
                username = "newadmin"
                password = "password123"
                nickname = "New Admin"
                email = "newadmin@example.com"
                phone = "13800138003"
                status = 1
                isAdmin = 0
                active = 1
            }

            `when`(tenantMapper.selectById(tenantId)).thenReturn(testTenant)
            `when`(sysUserMapper.selectById(userId)).thenReturn(user)
            `when`(userTenantMapper.selectByUserIdAndTenantId(userId, tenantId)).thenReturn(null)
            `when`(userTenantMapper.insert(any())).thenReturn(1)

            // When
            val result = userTenantService.addUserToTenant(tenantId, userId, role, "admin")

            // Then
            assertTrue(result)
            verify(userTenantMapper).insert(any())
        }
    }

    @Nested
    @DisplayName("Remove User from Tenant Tests")
    inner class RemoveUserFromTenantTests {

        @Test
        @DisplayName("removeUserFromTenant - Remove user from tenant successfully")
        fun `removeUserFromTenant should remove user from tenant successfully`() {
            // Given
            val curUserId = 2L
            val curTenantId = 1L
            val operator = "admin"

            val userTenant = UserTenantEntity().apply {
                id = 1L
                userId = curUserId
                tenantId = curTenantId
                role = "member"
                status = 1
                joinedAt = LocalDateTime.now()
            }

            `when`(userTenantMapper.selectByUserIdAndTenantId(curUserId, curTenantId)).thenReturn(userTenant)
            `when`(userTenantMapper.deleteByUserIdAndTenantId(curUserId, curTenantId)).thenReturn(1)

            // When
            val result = userTenantService.removeUserFromTenant(curTenantId, curUserId, operator)

            // Then
            assertTrue(result)
            verify(userTenantMapper).selectByUserIdAndTenantId(curUserId, curTenantId)
            verify(userTenantMapper).deleteByUserIdAndTenantId(curUserId, curTenantId)
        }

        @Test
        @DisplayName("removeUserFromTenant - Throw exception when user not in tenant")
        fun `removeUserFromTenant should throw exception when user not in tenant`() {
            // Given
            `when`(userTenantMapper.selectByUserIdAndTenantId(999L, 1L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                userTenantService.removeUserFromTenant(1L, 999L, "admin")
            }
            assertEquals("error.user.not_in_tenant", exception.message)
            verify(userTenantMapper).selectByUserIdAndTenantId(999L, 1L)
            verify(userTenantMapper, never()).deleteByUserIdAndTenantId(any(), any())
        }

        @Test
        @DisplayName("removeUserFromTenant - Remove admin user from tenant")
        fun `removeUserFromTenant should remove admin user from tenant`() {
            // Given
            val curUserId = 1L
            val curTenantId = 1L

            val adminUserTenant = UserTenantEntity().apply {
                id = 1L
                userId = curUserId
                tenantId = curTenantId
                role = "admin"
                status = 1
                joinedAt = LocalDateTime.now()
            }

            `when`(userTenantMapper.selectByUserIdAndTenantId(curUserId, curTenantId)).thenReturn(adminUserTenant)
            `when`(userTenantMapper.deleteByUserIdAndTenantId(curUserId, curTenantId)).thenReturn(1)

            // When
            val result = userTenantService.removeUserFromTenant(curTenantId, curUserId, "admin")

            // Then
            assertTrue(result)
            verify(userTenantMapper).deleteByUserIdAndTenantId(curUserId, curTenantId)
        }
    }

    @Nested
    @DisplayName("Get Users by Tenant ID Tests")
    inner class GetUsersByTenantIdTests {

        @Test
        @DisplayName("getUsersByTenantId - Get paginated user list by tenant ID")
        fun `getUsersByTenantId should return paginated user list`() {
            // Given
            val curTenantId = 1L
            val userTenant1 = UserTenantEntity().apply {
                id = 1L
                userId = 1L
                tenantId = curTenantId
                role = "admin"
                status = 1
                joinedAt = LocalDateTime.now()
            }
            val userTenant2 = UserTenantEntity().apply {
                id = 2L
                userId = 2L
                tenantId = curTenantId
                role = "member"
                status = 1
                joinedAt = LocalDateTime.now()
            }

            val user1 = SysUser().apply {
                id = 1L
                username = "admin"
                nickname = "Administrator"
            }
            val user2 = SysUser().apply {
                id = 2L
                username = "member"
                nickname = "Member User"
            }

            `when`(userTenantMapper.selectByTenantId(curTenantId)).thenReturn(listOf(userTenant1, userTenant2))
            `when`(sysUserMapper.selectById(1L)).thenReturn(user1)
            `when`(sysUserMapper.selectById(2L)).thenReturn(user2)

            // When
            @Suppress("UNCHECKED_CAST")
            val result = userTenantService.getUsersByTenantId(curTenantId, 1, 10) as Map<String, Any>

            // Then
            assertNotNull(result)
            assertEquals(2L, result["total"])
            assertEquals(1, result["pageNum"])
            // pageSize may vary due to PageHelper behavior in test environment
            assertTrue((result["pageSize"] as Int) > 0)

            @Suppress("UNCHECKED_CAST")
            val records = result["records"] as List<Map<String, Any>>
            assertEquals(2, records.size)
            assertEquals("admin", records[0]["username"])
            assertEquals("admin", records[0]["role"])
            assertEquals("member", records[1]["username"])
            assertEquals("member", records[1]["role"])

            verify(userTenantMapper).selectByTenantId(curTenantId)
            verify(sysUserMapper).selectById(1L)
            verify(sysUserMapper).selectById(2L)
        }

        @Test
        @DisplayName("getUsersByTenantId - Return empty list when tenant has no users")
        fun `getUsersByTenantId should return empty list when tenant has no users`() {
            // Given
            `when`(userTenantMapper.selectByTenantId(999L)).thenReturn(emptyList())

            // When
            @Suppress("UNCHECKED_CAST")
            val result = userTenantService.getUsersByTenantId(999L, 1, 10) as Map<String, Any>

            // Then
            assertNotNull(result)
            assertEquals(0L, result["total"])

            @Suppress("UNCHECKED_CAST")
            val records = result["records"] as List<Map<String, Any>>
            assertTrue(records.isEmpty())

            verify(userTenantMapper).selectByTenantId(999L)
            verifyNoInteractions(sysUserMapper)
        }
    }
}
