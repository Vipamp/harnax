package com.agnetix.harnax.admin.mapper

import com.agnetix.harnax.admin.entity.SysUser
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
 * SysUserMapper Integration Tests
 * Uses Testcontainers to start a real MySQL container for testing MyBatis SQL mappings
 *
 * @author agnetix
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SysUserMapperTest {

    companion object {
        @Container
        val mysqlContainer = MySQLContainer("mysql:8.0")
            .withDatabaseName("harnax_test")
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
    @DisplayName("Basic CRUD Tests")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - Query user by ID")
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
        @DisplayName("selectById - Return null when user not exists")
        fun `selectById should return null when user not exists`() {
            // When
            val user = sysUserMapper.selectById(999L)

            // Then
            assertNull(user)
        }

        @Test
        @DisplayName("selectById - Not return deleted user (active=0)")
        fun `selectById should not return deleted user`() {
            // When
            val user = sysUserMapper.selectById(5L)

            // Then
            assertNull(user)
        }

        @Test
        @DisplayName("insert - Create new user")
        fun `insert should create new user`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newUser = SysUser().apply {
                username = "newuser"
                password = "encrypted_password_123"
                nickname = "新用户"
                email = "newuser@example.com"
                phone = "13900139000"
                gender = 1
                avatar = "https://example.com/newuser.jpg"
                status = 1
                isAdmin = 0
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = sysUserMapper.insert(newUser)

            // Then
            assertEquals(1, result)
            assertTrue(newUser.id > 0)

            // Verify can be queried
            val insertedUser = sysUserMapper.selectById(newUser.id)
            assertNotNull(insertedUser)
            assertEquals("newuser", insertedUser.username)
            assertEquals("新用户", insertedUser.nickname)
        }

        @Test
        @DisplayName("updateById - Update user info")
        fun `updateById should update user info`() {
            // Given
            val userId = 1L
            val userBefore = sysUserMapper.selectById(userId)
            assertNotNull(userBefore)
            val oldUpdateTime = userBefore.updateTime

            // When
            userBefore.nickname = "更新后的昵称"
            userBefore.email = "updated@example.com"
            userBefore.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val result = sysUserMapper.updateById(userBefore)

            // Then
            assertEquals(1, result)
            val userAfter = sysUserMapper.selectById(userId)
            assertNotNull(userAfter)
            assertEquals("更新后的昵称", userAfter.nickname)
            assertEquals("updated@example.com", userAfter.email)
        }

        @Test
        @DisplayName("deleteById - Logically delete user")
        fun `deleteById should logically delete user`() {
            // Given
            val userId = 2L
            val userBefore = sysUserMapper.selectById(userId)
            assertNotNull(userBefore)
            assertEquals(1, userBefore.active)

            // When
            val result = sysUserMapper.deleteById(userId)

            // Then
            assertEquals(1, result)

            // selectById should not return it (because active=0)
            val deletedUser = sysUserMapper.selectById(userId)
            assertNull(deletedUser)
        }

        @Test
        @DisplayName("deleteById - Return 0 when user not exists")
        fun `deleteById should return 0 when user not exists`() {
            // When
            val result = sysUserMapper.deleteById(999L)

            // Then
            assertEquals(0, result)
        }

        @Test
        @DisplayName("deleteById - Not delete already deleted user")
        fun `deleteById should not delete already deleted user`() {
            // When - id=5 is deleted user
            val result = sysUserMapper.deleteById(5L)

            // Then
            assertEquals(0, result)
        }
    }

    @Nested
    @DisplayName("Custom Query Tests")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectUserList - Query all user list")
        fun `selectUserList should return all users`() {
            // When
            val users = sysUserMapper.selectUserList(null, null, null)

            // Then
            assertTrue(users.isNotEmpty())
            // Should return all active users (at least 4 users with active=1)
            assertTrue(users.size >= 4)
        }

        @Test
        @DisplayName("selectUserList - Filter by username")
        fun `selectUserList should filter by username`() {
            // When
            val users = sysUserMapper.selectUserList("testuser", null, null)

            // Then
            assertTrue(users.isNotEmpty())
            users.forEach {
                assertTrue(it.username.contains("testuser"))
            }
        }

        @Test
        @DisplayName("selectUserList - Filter by status")
        fun `selectUserList should filter by status`() {
            // When
            val users = sysUserMapper.selectUserList(null, 0, null)

            // Then
            assertTrue(users.isNotEmpty())
            users.forEach {
                assertEquals(0, it.status)
            }
        }

        @Test
        @DisplayName("selectUserList - Filter by keyword")
        fun `selectUserList should filter by keyword`() {
            // When
            val users = sysUserMapper.selectUserList("testuser", null, null)

            // Then
            assertTrue(users.isNotEmpty())
            users.forEach {
                assertTrue(
                    it.username.contains("testuser") ||
                        it.nickname.contains("testuser") ||
                        it.email.contains("testuser") ||
                        it.phone.contains("testuser"),
                )
            }
        }

        @Test
        @DisplayName("selectByUsername - Query user by username")
        fun `selectByUsername should return user by username`() {
            // When
            val user = sysUserMapper.selectByUsername("testuser1")

            // Then
            assertNotNull(user)
            assertEquals("testuser1", user.username)
            assertEquals("测试用户1", user.nickname)
        }

        @Test
        @DisplayName("selectByUsername - Return null when username not exists")
        fun `selectByUsername should return null when username not exists`() {
            // When
            val user = sysUserMapper.selectByUsername("nonexistent")

            // Then
            assertNull(user)
        }

        @Test
        @DisplayName("selectByPhone - Query user by phone")
        fun `selectByPhone should return user by phone`() {
            // When
            val user = sysUserMapper.selectByPhone("13800138001")

            // Then
            assertNotNull(user)
            assertEquals("13800138001", user.phone)
        }

        @Test
        @DisplayName("selectByEmail - Query user by email")
        fun `selectByEmail should return user by email`() {
            // When
            val user = sysUserMapper.selectByEmail("test1@example.com")

            // Then
            assertNotNull(user)
            assertEquals("test1@example.com", user.email)
        }
    }

    @Nested
    @DisplayName("Status Management Tests")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - Update user status")
        fun `updateStatus should update user status`() {
            // Given
            val userId = 1L
            val newStatus = 0

            // When
            val result = sysUserMapper.updateStatus(userId, newStatus)
            val updatedUser = sysUserMapper.selectById(userId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedUser)
            assertEquals(newStatus, updatedUser.status)
            assertNotNull(updatedUser.updateTime)
        }

        @Test
        @DisplayName("updateStatus - Return 0 when user not exists")
        fun `updateStatus should return 0 when user not exists`() {
            // When
            val result = sysUserMapper.updateStatus(999L, 0)

            // Then
            assertEquals(0, result)
        }
    }

    @Nested
    @DisplayName("Other Feature Tests")
    inner class OtherFeatureTests {

        @Test
        @DisplayName("updateLastLoginTime - Update last login time")
        fun `updateLastLoginTime should update last login time`() {
            // Given
            val userId = 1L
            val loginTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

            // When
            val result = sysUserMapper.updateLastLoginTime(userId, loginTime)
            val updatedUser = sysUserMapper.selectById(userId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedUser)
            assertNotNull(updatedUser.lastLoginTime)
            assertEquals(loginTime, updatedUser.lastLoginTime)
        }

        @Test
        @DisplayName("updateLastLoginTime - Return 0 when user not exists")
        fun `updateLastLoginTime should return 0 when user not exists`() {
            // Given
            val loginTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

            // When
            val result = sysUserMapper.updateLastLoginTime(999L, loginTime)

            // Then
            assertEquals(0, result)
        }
    }
}
