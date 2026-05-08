package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.entity.TenantEntity
import com.vipamp.vipclaw.admin.entity.UserTenantEntity
import com.vipamp.vipclaw.admin.exception.BizException
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
import java.time.LocalDateTime

/**
 * UserTenantServiceImpl 单元测试
 * 使用 Mockito 模拟 Mapper 层依赖
 *
 * @author vipamp
 * @since 2026-04-28
 */
@ExtendWith(MockitoExtension::class)
class UserTenantServiceImplTest {

    @Mock
    private lateinit var userTenantMapper: UserTenantMapper

    @Mock
    private lateinit var tenantMapper: TenantMapper

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
            name = "测试租户"
            status = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
    }

    @Nested
    @DisplayName("获取用户租户列表测试")
    inner class GetUserTenantsTests {

        @Test
        @DisplayName("getUserTenants - 正常获取用户所属租户列表")
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
                name = "租户2"
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
            assertEquals("测试租户", result[0].name)
            assertEquals(1, result[0].status)
            assertEquals("租户2", result[1].name)
            verify(userTenantMapper).selectByUserId(1L)
            verify(tenantMapper).selectById(1L)
            verify(tenantMapper).selectById(2L)
        }

        @Test
        @DisplayName("getUserTenants - 用户无租户时返回空列表")
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
        @DisplayName("getUserTenants - 过滤已删除的租户")
        fun `getUserTenants should filter out deleted tenants`() {
            // Given
            val deletedTenant = TenantEntity().apply {
                id = 999L
                name = "已删除租户"
                status = 0
                creator = "admin"
                active = 0 // 已删除
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
            `when`(tenantMapper.selectById(999L)).thenReturn(null) // 租户已被删除

            // When
            val result = userTenantService.getUserTenants(1L)

            // Then
            assertNotNull(result)
            assertTrue(result.isEmpty()) // 已删除租户应被过滤
            verify(userTenantMapper).selectByUserId(1L)
            verify(tenantMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("获取用户租户信息测试")
    inner class GetUserTenantInfoTests {

        @Test
        @DisplayName("getUserTenantInfo - 正常获取用户在租户中的信息")
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
        @DisplayName("getUserTenantInfo - 用户不在租户中时返回null")
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
    @DisplayName("检查用户是否在租户中测试")
    inner class IsUserInTenantTests {

        @Test
        @DisplayName("isUserInTenant - 用户正常在租户中")
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
        @DisplayName("isUserInTenant - 用户不在租户中返回false")
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
        @DisplayName("isUserInTenant - 用户被禁用时返回false")
        fun `isUserInTenant should return false when user is disabled`() {
            // Given
            val disabledUserTenant = UserTenantEntity().apply {
                id = 1L
                userId = 1L
                tenantId = 1L
                role = "member"
                status = 0 // 禁用状态
                joinedAt = LocalDateTime.now()
            }

            `when`(userTenantMapper.selectByUserIdAndTenantId(1L, 1L)).thenReturn(disabledUserTenant)

            // When
            val result = userTenantService.isUserInTenant(1L, 1L)

            // Then
            assertFalse(result) // status=0 应返回 false
            verify(userTenantMapper).selectByUserIdAndTenantId(1L, 1L)
        }
    }

    @Nested
    @DisplayName("更新用户角色测试")
    inner class UpdateUserRoleTests {

        @Test
        @DisplayName("updateUserRole - 正常更新用户角色")
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
        @DisplayName("updateUserRole - 用户不在租户中时抛出异常")
        fun `updateUserRole should throw exception when user not in tenant`() {
            // Given
            `when`(userTenantMapper.selectByUserIdAndTenantId(999L, 1L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                userTenantService.updateUserRole(999L, 1L, "member")
            }
            assertEquals("用户不在该租户中", exception.message)
            verify(userTenantMapper).selectByUserIdAndTenantId(999L, 1L)
            verify(userTenantMapper, never()).updateRole(any(), any(), any())
        }

        @Test
        @DisplayName("updateUserRole - 从member升级到admin")
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
        @DisplayName("updateUserRole - 从admin降级到member")
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
}
