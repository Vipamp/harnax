package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.SysUserCreateRequest
import com.vipamp.vipclaw.admin.dto.SysUserUpdateRequest
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.SysUserMapper
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.bind.Nested
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SysUserServiceImpl 集成测试
 * 使用 Testcontainers 启动真实的 MySQL 容器进行测试
 * 测试 Service 层对数据库的实际操作
 *
 * @author vipamp
 * @since 2026-04-19
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SysUserServiceImplIntegrationTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("vipclaw_test")
            .withUsername("root")
            .withPassword("test")
            .withInitScript("schema-test.sql")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", mysqlContainer::getJdbcUrl)
            registry.add("spring.datasource.username", mysqlContainer::getUsername)
            registry.add("spring.datasource.password", mysqlContainer::getPassword)
        }
    }

    @Autowired
    private lateinit var sysUserService: SysUserServiceImpl

    @Autowired
    private lateinit var sysUserMapper: SysUserMapper

    @Nested
    @DisplayName("分页查询测试")
    inner class PaginationTests {

        @Test
        @DisplayName("getUserPage - 正常分页查询")
        fun `getUserPage should return paginated results`() {
            // When
            val page = sysUserService.getUserPage(null, null, 1, 2)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 4) // schema-test.sql 中有5条，但deleted_user的active=0
            assertEquals(2, page.size)
            assertEquals(1, page.current)
            assertEquals(2, page.records.size)
        }

        @Test
        @DisplayName("getUserPage - 关键字搜索")
        fun `getUserPage should filter by keyword`() {
            // When
            val page = sysUserService.getUserPage("testuser", null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 2)
            assertTrue(page.records.all { it.username.contains("testuser") })
        }

        @Test
        @DisplayName("getUserPage - 状态过滤")
        fun `getUserPage should filter by status`() {
            // When
            val page = sysUserService.getUserPage(null, 0, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 1)
            assertTrue(page.records.all { it.status == 0 })
        }

        @Test
        @DisplayName("getUserPage - 超出范围的页码")
        fun `getUserPage should return empty list when page out of range`() {
            // When
            val page = sysUserService.getUserPage(null, null, 100, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.records.isEmpty())
        }
    }

    @Nested
    @DisplayName("查询用户详情测试")
    inner class GetUserByIdTests {

        @Test
        @DisplayName("getUserById - 查询存在的用户")
        fun `getUserById should return user when exists`() {
            // When
            val user = sysUserService.getUserById(1L)

            // Then
            assertNotNull(user)
            assertEquals(1L, user.id)
            assertEquals("testuser1", user.username)
            assertEquals("测试用户1", user.nickname)
        }

        @Test
        @DisplayName("getUserById - 查询不存在的用户应该抛出异常")
        fun `getUserById should throw BizException when user not found`() {
            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.getUserById(999L)
            }
            assertEquals("用户不存在", exception.message)
        }

        @Test
        @DisplayName("getUserById - 查询已删除用户应该抛出异常")
        fun `getUserById should throw BizException for deleted user`() {
            // When & Then - deleted_user 的 id 是 5
            val exception = assertThrows<BizException> {
                sysUserService.getUserById(5L)
            }
            assertEquals("用户不存在", exception.message)
        }
    }

    @Nested
    @DisplayName("创建用户测试")
    inner class CreateUserTests {

        @Test
        @DisplayName("createUser - 创建成功")
        fun `createUser should create user successfully`() {
            // Given
            val request = SysUserCreateRequest(
                username = "newuser",
                password = "password123",
                nickname = "新用户",
                email = "newuser@example.com",
                phone = "13900139000",
                gender = 1,
                avatar = "https://example.com/new.jpg",
                status = 1,
                isAdmin = 0
            )

            // When
            val result = sysUserService.createUser(request)

            // Then
            assertTrue(result)

            // 验证用户可以查询到
            val user = sysUserMapper.selectByUsername("newuser")
            assertNotNull(user)
            assertEquals("新用户", user?.nickname)
            assertEquals("newuser@example.com", user?.email)
        }

        @Test
        @DisplayName("createUser - 用户名已存在应该抛出异常")
        fun `createUser should throw BizException when username exists`() {
            // Given
            val request = SysUserCreateRequest(
                username = "testuser1", // 已存在
                password = "password123",
                nickname = "重复用户",
                email = "duplicate@example.com",
                phone = "13900139001",
                gender = 1,
                avatar = "https://example.com/dup.jpg",
                status = 1,
                isAdmin = 0
            )

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.createUser(request)
            }
            assertEquals("用户名已存在", exception.message)
        }
    }

    @Nested
    @DisplayName("更新用户测试")
    inner class UpdateUserTests {

        @Test
        @DisplayName("updateUser - 更新部分字段")
        fun `updateUser should update partial fields`() {
            // Given
            val request = SysUserUpdateRequest(
                nickname = "更新后的昵称",
                email = "updated@example.com"
            )

            // When
            val result = sysUserService.updateUser(1L, request)

            // Then
            assertTrue(result)

            // 验证更新成功
            val user = sysUserMapper.selectById(1L)
            assertEquals("更新后的昵称", user?.nickname)
            assertEquals("updated@example.com", user?.email)
            // 未更新的字段保持原值
            assertEquals("testuser1", user?.username)
        }

        @Test
        @DisplayName("updateUser - 更新用户名")
        fun `updateUser should update username when not duplicate`() {
            // Given
            val request = SysUserUpdateRequest(
                username = "updated_username"
            )

            // When
            val result = sysUserService.updateUser(2L, request)

            // Then
            assertTrue(result)

            // 验证用户名已更新
            val user = sysUserMapper.selectById(2L)
            assertEquals("updated_username", user?.username)
        }

        @Test
        @DisplayName("updateUser - 用户名已存在应该抛出异常")
        fun `updateUser should throw BizException when new username exists`() {
            // Given
            val request = SysUserUpdateRequest(
                username = "testuser1" // 已被 id=1 的用户使用
            )

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.updateUser(2L, request)
            }
            assertEquals("用户名已存在", exception.message)
        }

        @Test
        @DisplayName("updateUser - 用户不存在应该抛出异常")
        fun `updateUser should throw BizException when user not found`() {
            // Given
            val request = SysUserUpdateRequest(
                nickname = "新昵称"
            )

            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.updateUser(999L, request)
            }
            assertEquals("用户不存在", exception.message)
        }
    }

    @Nested
    @DisplayName("切换用户状态测试")
    inner class ToggleUserStatusTests {

        @Test
        @DisplayName("toggleUserStatus - 禁用用户")
        fun `toggleUserStatus should disable user`() {
            // When
            val result = sysUserService.toggleUserStatus(1L, 0)

            // Then
            assertTrue(result)

            // 验证状态已更新
            val user = sysUserMapper.selectById(1L)
            assertEquals(0, user?.status)
        }

        @Test
        @DisplayName("toggleUserStatus - 启用用户")
        fun `toggleUserStatus should enable user`() {
            // Given - 先禁用
            sysUserService.toggleUserStatus(2L, 0)

            // When - 再启用
            val result = sysUserService.toggleUserStatus(2L, 1)

            // Then
            assertTrue(result)

            // 验证状态已更新
            val user = sysUserMapper.selectById(2L)
            assertEquals(1, user?.status)
        }

        @Test
        @DisplayName("toggleUserStatus - 用户不存在应该抛出异常")
        fun `toggleUserStatus should throw BizException when user not found`() {
            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.toggleUserStatus(999L, 0)
            }
            assertEquals("用户不存在", exception.message)
        }
    }

    @Nested
    @DisplayName("删除用户测试")
    inner class DeleteUserTests {

        @Test
        @DisplayName("deleteUser - 逻辑删除成功")
        fun `deleteUser should logically delete user`() {
            // When
            val result = sysUserService.deleteUser(3L)

            // Then
            assertTrue(result)

            // 验证 active 已变为 0
            val user = sysUserMapper.selectById(3L)
            assertEquals(0, user?.active)

            // getUserById 应该查不到（因为使用 selectActiveById）
            val exception = assertThrows<BizException> {
                sysUserService.getUserById(3L)
            }
            assertEquals("用户不存在", exception.message)
        }

        @Test
        @DisplayName("deleteUser - 删除不存在的用户应该抛出异常")
        fun `deleteUser should throw BizException when user not found`() {
            // When & Then
            val exception = assertThrows<BizException> {
                sysUserService.deleteUser(999L)
            }
            assertEquals("用户不存在", exception.message)
        }

        @Test
        @DisplayName("deleteUser - 删除已删除用户应该抛出异常")
        fun `deleteUser should throw BizException when user already deleted`() {
            // When & Then - deleted_user 的 id 是 5
            val exception = assertThrows<BizException> {
                sysUserService.deleteUser(5L)
            }
            assertEquals("用户不存在", exception.message)
        }
    }

    @Nested
    @DisplayName("根据用户名查询测试")
    inner class GetByUsernameTests {

        @Test
        @DisplayName("getByUsername - 查询存在的用户")
        fun `getByUsername should return user when exists`() {
            // When
            val user = sysUserService.getByUsername("testuser1")

            // Then
            assertNotNull(user)
            assertEquals("testuser1", user?.username)
            assertEquals("测试用户1", user?.nickname)
        }

        @Test
        @DisplayName("getByUsername - 查询不存在的用户返回null")
        fun `getByUsername should return null when user not exists`() {
            // When
            val user = sysUserService.getByUsername("nonexistent")

            // Then
            assertNull(user)
        }

        @Test
        @DisplayName("getByUsername - 不查询已删除用户")
        fun `getByUsername should not return deleted user`() {
            // When
            val user = sysUserService.getByUsername("deleted_user")

            // Then
            assertNull(user) // active = 0 的用户不会被查询到
        }
    }

    @Nested
    @DisplayName("完整业务流程测试")
    inner class BusinessFlowTests {

        @Test
        @DisplayName("完整流程：创建 - 查询 - 更新 - 禁用 - 删除")
        fun `complete flow create query update disable delete`() {
            // 1. 创建用户
            val createRequest = SysUserCreateRequest(
                username = "flowtest",
                password = "password123",
                nickname = "流程测试用户",
                email = "flow@test.com",
                phone = "13900139000",
                gender = 1,
                avatar = "https://example.com/flow.jpg",
                status = 1,
                isAdmin = 0
            )
            assertTrue(sysUserService.createUser(createRequest))

            // 2. 查询用户
            val user = sysUserMapper.selectByUsername("flowtest")
            assertNotNull(user)
            val userId = user!!.id

            // 3. 更新用户
            val updateRequest = SysUserUpdateRequest(
                nickname = "更新后的流程测试用户",
                email = "updated@test.com"
            )
            assertTrue(sysUserService.updateUser(userId, updateRequest))

            val updatedUser = sysUserService.getUserById(userId)
            assertEquals("更新后的流程测试用户", updatedUser.nickname)
            assertEquals("updated@test.com", updatedUser.email)

            // 4. 禁用用户
            assertTrue(sysUserService.toggleUserStatus(userId, 0))
            val disabledUser = sysUserMapper.selectById(userId)
            assertEquals(0, disabledUser?.status)

            // 5. 删除用户
            assertTrue(sysUserService.deleteUser(userId))
            val deletedUser = sysUserMapper.selectById(userId)
            assertEquals(0, deletedUser?.active)

            // 6. 验证删除后无法查询
            assertThrows<BizException> {
                sysUserService.getUserById(userId)
            }
        }
    }
}
