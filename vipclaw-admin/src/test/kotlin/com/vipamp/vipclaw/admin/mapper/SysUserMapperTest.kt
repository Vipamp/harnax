package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.SysUser
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SysUserMapper 集成测试
 * 使用 Testcontainers 启动真实的 MySQL 容器测试 MyBatis SQL 映射
 *
 * @author vipamp
 * @since 2026-04-22
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SysUserMapperTest {

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
    private lateinit var sysUserMapper: SysUserMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询用户")
        fun `selectById should return user by id`() {
            // When
            val user = sysUserMapper.selectById(1L)

            // Then
            assertNotNull(user)
            assertEquals(1L, user.id)
            assertEquals("testuser1", user.username)
            assertEquals("password123", user.password)
            assertEquals("测试用户1", user.nickname)
            assertEquals("test1@example.com", user.email)
            assertEquals("13800138001", user.phone)
            assertEquals(1, user.gender)
            assertEquals("https://example.com/avatar1.jpg", user.avatar)
            assertEquals(1, user.status)
            assertEquals(0, user.isAdmin)
            assertEquals(1, user.active)
            assertNotNull(user.createTime)
            assertNotNull(user.updateTime)
        }

        @Test
        @DisplayName("selectById - 查询不存在的用户返回 null")
        fun `selectById should return null when user not exists`() {
            // When
            val user = sysUserMapper.selectById(999L)

            // Then
            assertNull(user)
        }

        @Test
        @DisplayName("insert - 插入新用户")
        fun `insert should create new user`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newUser = SysUser().apply {
                username = "newuser"
                password = "encrypted_password_123"
                nickname = "新用户"
                email = "new@example.com"
                phone = "13900139000"
                gender = 1
                avatar = "https://example.com/avatar.jpg"
                status = 1
                isAdmin = 0
                lastLoginTime = null  // 新用户未登录
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = sysUserMapper.insert(newUser)

            // Then
            assertEquals(1, result)
            assertNotNull(newUser.id)
            assertTrue(newUser.id > 0)

            // 验证用户可以查询到且所有字段正确
            val savedUser = sysUserMapper.selectById(newUser.id)
            assertNotNull(savedUser)
            assertEquals(newUser.id, savedUser.id)
            assertEquals("newuser", savedUser.username)
            assertEquals("encrypted_password_123", savedUser.password)
            assertEquals("新用户", savedUser.nickname)
            assertEquals("new@example.com", savedUser.email)
            assertEquals("13900139000", savedUser.phone)
            assertEquals(1, savedUser.gender)
            assertEquals("https://example.com/avatar.jpg", savedUser.avatar)
            assertEquals(1, savedUser.status)
            assertEquals(0, savedUser.isAdmin)
            assertEquals(1, savedUser.active)
            assertNull(savedUser.lastLoginTime)  // 验证 lastLoginTime 为 null
            assertEquals(now, savedUser.createTime)
            assertEquals(now, savedUser.updateTime)
        }

        @Test
        @DisplayName("updateById - 更新用户信息")
        fun `updateById should update user`() {
            // Given
            val user = sysUserMapper.selectById(1L)
            assertNotNull(user)

            // 记录原始值
            val originalUsername = user.username
            val originalPassword = user.password
            val originalPhone = user.phone
            val originalGender = user.gender
            val originalStatus = user.status
            val originalIsAdmin = user.isAdmin
            val originalActive = user.active
            val originalCreateTime = user.createTime

            // 更新部分字段
            val newUpdateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            user.nickname = "更新后的昵称"
            user.email = "updated@example.com"
            user.avatar = "https://example.com/new-avatar.jpg"
            user.updateTime = newUpdateTime

            // When
            val result = sysUserMapper.updateById(user)

            // Then
            assertEquals(1, result)

            // 验证更新成功,所有字段正确
            val updatedUser = sysUserMapper.selectById(1L)
            assertNotNull(updatedUser)
            assertEquals(1L, updatedUser.id)
            assertEquals(originalUsername, updatedUser.username)
            assertEquals(originalPassword, updatedUser.password)
            assertEquals("更新后的昵称", updatedUser.nickname)
            assertEquals("updated@example.com", updatedUser.email)
            assertEquals(originalPhone, updatedUser.phone)
            assertEquals(originalGender, updatedUser.gender)
            assertEquals("https://example.com/new-avatar.jpg", updatedUser.avatar)
            assertEquals(originalStatus, updatedUser.status)
            assertEquals(originalIsAdmin, updatedUser.isAdmin)
            assertEquals(originalActive, updatedUser.active)
            assertEquals(originalCreateTime, updatedUser.createTime)
            assertEquals(newUpdateTime, updatedUser.updateTime)
        }
    }

    @Nested
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectUserList - 查询所有用户")
        fun `selectUserList should return all users when no filter`() {
            // When
            val users = sysUserMapper.selectUserList(null, null)

            // Then
            assertTrue(users.size >= 4) // schema-test.sql 中有 4 个 active=1 的用户
        }

        @Test
        @DisplayName("selectUserList - 按关键字搜索")
        fun `selectUserList should filter by keyword`() {
            // When
            val users = sysUserMapper.selectUserList("testuser", null)

            // Then
            assertTrue(users.isNotEmpty())
            assertTrue(users.all {
                it.username.contains("testuser") ||
                        it.nickname.contains("testuser") ||
                        it.email.contains("testuser") ||
                        it.phone.contains("testuser")
            })
        }

        @Test
        @DisplayName("selectUserList - 按状态过滤")
        fun `selectUserList should filter by status`() {
            // When
            val users = sysUserMapper.selectUserList(null, 0)

            // Then
            assertTrue(users.isNotEmpty())
            assertTrue(users.all { it.status == 0 })
        }

        @Test
        @DisplayName("selectUserList - 组合条件查询")
        fun `selectUserList should filter by keyword and status`() {
            // When
            val users = sysUserMapper.selectUserList("testuser", 1)

            // Then
            assertTrue(users.isNotEmpty())
            assertTrue(users.all {
                it.status == 1 && (
                        it.username.contains("testuser") ||
                                it.nickname.contains("testuser")
                        )
            })
        }

        @Test
        @DisplayName("selectByUsername - 根据用户名查询")
        fun `selectByUsername should return user by username`() {
            // When
            val user = sysUserMapper.selectByUsername("testuser1")

            // Then
            assertNotNull(user)
            assertEquals("testuser1", user.username)
            assertEquals("测试用户1", user.nickname)
        }

        @Test
        @DisplayName("selectByUsername - 查询不存在的用户名返回 null")
        fun `selectByUsername should return null when username not exists`() {
            // When
            val user = sysUserMapper.selectByUsername("nonexistent")

            // Then
            assertNull(user)
        }

        @Test
        @DisplayName("selectActiveById - 查询活跃用户")
        fun `selectActiveById should return active user`() {
            // When
            val user = sysUserMapper.selectActiveById(1L)

            // Then
            assertNotNull(user)
            assertEquals(1L, user.id)
            assertEquals(1, user.active)
        }

        @Test
        @DisplayName("selectActiveById - 查询已删除用户返回 null")
        fun `selectActiveById should return null for deleted user`() {
            // When - id=5 是 deleted_user, active=0
            val user = sysUserMapper.selectActiveById(5L)

            // Then
            assertNull(user)
        }
    }

    @Nested
    @DisplayName("状态更新测试")
    inner class StatusUpdateTests {

        @Test
        @DisplayName("updateStatus - 禁用用户")
        fun `updateStatus should disable user`() {
            // When
            val result = sysUserMapper.updateStatus(1L, 0)

            // Then
            assertEquals(1, result)

            // 验证状态已更新
            val user = sysUserMapper.selectById(1L)
            assertNotNull(user)
            assertEquals(0, user.status)
        }

        @Test
        @DisplayName("updateStatus - 启用用户")
        fun `updateStatus should enable user`() {
            // Given - 先禁用
            sysUserMapper.updateStatus(2L, 0)

            // When - 再启用
            val result = sysUserMapper.updateStatus(2L, 1)

            // Then
            assertEquals(1, result)

            // 验证状态已更新
            val user = sysUserMapper.selectById(2L)
            assertNotNull(user)
            assertEquals(1, user.status)
        }
    }

    @Nested
    @DisplayName("逻辑删除测试")
    inner class LogicalDeleteTests {

        @Test
        @DisplayName("logicalDelete - 逻辑删除用户")
        fun `logicalDelete should soft delete user`() {
            // When
            val result = sysUserMapper.logicalDelete(3L)

            // Then
            assertEquals(1, result)

            // 验证 active 已变为 0
            val user = sysUserMapper.selectById(3L)
            assertNotNull(user)
            assertEquals(0, user.active)

            // selectActiveById 应该查不到
            val activeUser = sysUserMapper.selectActiveById(3L)
            assertNull(activeUser)
        }

        @Test
        @DisplayName("logicalDelete - 删除已删除用户不影响")
        fun `logicalDelete should not affect already deleted user`() {
            // When - id=5 已经是 deleted_user
            val result = sysUserMapper.logicalDelete(5L)

            // Then
            assertEquals(0, result) // SQL 仍然执行成功

            // 验证 active 仍然是 0
            val user = sysUserMapper.selectById(5L)
            assertNotNull(user)
            assertEquals(0, user.active)
        }
    }

    @Nested
    @DisplayName("完整业务流程测试")
    inner class BusinessFlowTests {

        @Test
        @DisplayName("完整流程：插入 - 查询 - 更新 - 禁用 - 删除")
        fun `complete flow insert query update disable delete`() {
            // 1. 插入用户
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newUser = SysUser().apply {
                username = "flowtest"
                password = "password123"
                nickname = "流程测试用户"
                email = "flow@test.com"
                phone = "13900139001"
                gender = 1
                avatar = "https://example.com/flow.jpg"
                status = 1
                isAdmin = 0
                lastLoginTime = null  // 新用户未登录
                active = 1
                createTime = now
                updateTime = now
            }
            assertEquals(1, sysUserMapper.insert(newUser))
            assertNotNull(newUser.id)

            // 2. 查询用户 - 验证所有字段
            val user = sysUserMapper.selectByUsername("flowtest")
            assertNotNull(user)
            assertEquals(newUser.id, user.id)
            assertEquals("flowtest", user.username)
            assertEquals("password123", user.password)
            assertEquals("流程测试用户", user.nickname)
            assertEquals("flow@test.com", user.email)
            assertEquals("13900139001", user.phone)
            assertEquals(1, user.gender)
            assertEquals("https://example.com/flow.jpg", user.avatar)
            assertEquals(1, user.status)
            assertEquals(0, user.isAdmin)
            assertEquals(1, user.active)
            assertNull(user.lastLoginTime)  // 验证 lastLoginTime 为 null
            assertEquals(now, user.createTime)
            assertEquals(now, user.updateTime)

            // 3. 更新用户
            val newUpdateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            user.nickname = "更新后的流程测试用户"
            user.email = "updated@test.com"
            user.avatar = "https://example.com/updated.jpg"
            user.updateTime = newUpdateTime
            assertEquals(1, sysUserMapper.updateById(user))

            val updatedUser = sysUserMapper.selectById(newUser.id)
            assertNotNull(updatedUser)
            assertEquals("更新后的流程测试用户", updatedUser.nickname)
            assertEquals("updated@test.com", updatedUser.email)
            assertEquals("https://example.com/updated.jpg", updatedUser.avatar)
            assertEquals(newUpdateTime, updatedUser.updateTime)
            // 验证未更新的字段保持不变
            assertEquals("flowtest", updatedUser.username)
            assertEquals("password123", updatedUser.password)
            assertEquals("13900139001", updatedUser.phone)
            assertEquals(1, updatedUser.gender)
            assertEquals(1, updatedUser.status)
            assertEquals(0, updatedUser.isAdmin)
            assertEquals(1, updatedUser.active)
            assertEquals(now, updatedUser.createTime)

            // 4. 禁用用户
            assertEquals(1, sysUserMapper.updateStatus(newUser.id, 0))
            val disabledUser = sysUserMapper.selectById(newUser.id)
            assertNotNull(disabledUser)
            assertEquals(0, disabledUser.status)
            // 验证其他字段不变
            assertEquals("flowtest", disabledUser.username)
            assertEquals("更新后的流程测试用户", disabledUser.nickname)
            assertEquals(1, disabledUser.active)

            // 5. 逻辑删除用户
            assertEquals(1, sysUserMapper.logicalDelete(newUser.id))
            val deletedUser = sysUserMapper.selectById(newUser.id)
            assertNotNull(deletedUser)
            assertEquals(0, deletedUser.active)
            // 验证其他字段不变
            assertEquals("flowtest", deletedUser.username)
            assertEquals(0, deletedUser.status)
            assertEquals("更新后的流程测试用户", deletedUser.nickname)

            // 6. 验证 selectActiveById 查不到
            val activeUser = sysUserMapper.selectActiveById(newUser.id)
            assertNull(activeUser)
        }
    }

    @Nested
    @DisplayName("登录时间更新测试")
    inner class LastLoginTimeTests {

        @Test
        @DisplayName("updateLastLoginTime - 更新用户登录时间")
        fun `updateLastLoginTime should update last login time`() {
            // Given
            val userId = 1L
            val userBefore = sysUserMapper.selectById(userId)
            assertNotNull(userBefore)
            assertNull(userBefore.lastLoginTime)  // 初始为 null
            val loginTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

            // When
            val result = sysUserMapper.updateLastLoginTime(userId, loginTime)

            // Then
            assertEquals(1, result)
            val userAfter = sysUserMapper.selectById(userId)
            assertNotNull(userAfter)
            assertNotNull(userAfter.lastLoginTime)
            // 验证登录时间已更新
            assertEquals(loginTime, userAfter.lastLoginTime)
        }

        @Test
        @DisplayName("updateLastLoginTime - 更新不存在的用户返回 0")
        fun `updateLastLoginTime should return 0 when user not exists`() {
            // Given
            val loginTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

            // When
            val result = sysUserMapper.updateLastLoginTime(999L, loginTime)

            // Then
            assertEquals(0, result)
        }

        @Test
        @DisplayName("updateLastLoginTime - 不更新已删除用户的登录时间")
        fun `updateLastLoginTime should not update deleted user`() {
            // Given
            val deletedUserId = 5L  // schema-test.sql 中 active=0 的用户
            val userBefore = sysUserMapper.selectById(deletedUserId)
            assertNotNull(userBefore)
            assertEquals(0, userBefore.active)
            val loginTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

            // When
            val result = sysUserMapper.updateLastLoginTime(deletedUserId, loginTime)

            // Then
            assertEquals(0, result)  // 已删除用户不应该被更新
        }
    }
}
