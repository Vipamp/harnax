package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.entity.TenantEntity
import com.agnetix.harnax.entity.UserTenantEntity
import org.junit.jupiter.api.BeforeEach
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
 * UserTenantMapper Integration Tests
 * Uses Testcontainers to start a real MySQL container for testing MyBatis SQL mappings
 *
 * @author agnetix
 * @since 2026-05-16
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UserTenantMapperTest {

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
    private lateinit var userTenantMapper: UserTenantMapper

    @Autowired
    private lateinit var sysUserMapper: SysUserMapper

    @Autowired
    private lateinit var tenantMapper: TenantMapper

    private var testUserId: Long = 0L
    private var testTenantId: Long = 0L

    @BeforeEach
    fun setUp() {
        // Create test user
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val user = SysUser().apply {
            username = "usertenant_test_user"
            password = "password123"
            nickname = "UserTenant Test User"
            email = "usertenant@test.com"
            phone = "13900139000"
            gender = 1
            status = 1
            isAdmin = 0
            active = 1
            createTime = now
            updateTime = now
        }
        sysUserMapper.insert(user)
        testUserId = user.id

        // Create test tenant
        val tenant = TenantEntity().apply {
            name = "UserTenant Test Tenant"
            status = 1
            creator = "admin"
            active = 1
            createTime = now
            updateTime = now
        }
        tenantMapper.insert(tenant)
        testTenantId = tenant.id
    }

    @Nested
    @DisplayName("Insert Tests")
    inner class InsertTests {

        @Test
        @DisplayName("insert - Create new user-tenant association")
        fun `insert should create new user-tenant association`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val userTenant = UserTenantEntity().apply {
                userId = testUserId
                tenantId = testTenantId
                role = "member"
                status = 1
                joinedAt = now
            }

            // When
            val result = userTenantMapper.insert(userTenant)

            // Then
            assertEquals(1, result)
            assertTrue(userTenant.id > 0)

            // Verify can be queried
            val inserted = userTenantMapper.selectByUserIdAndTenantId(testUserId, testTenantId)
            assertNotNull(inserted)
            assertEquals(testUserId, inserted.userId)
            assertEquals(testTenantId, inserted.tenantId)
            assertEquals("member", inserted.role)
            assertEquals(1, inserted.status)
        }
    }

    @Nested
    @DisplayName("Query Tests")
    inner class QueryTests {

        @Test
        @DisplayName("selectByUserId - Return tenant list by user ID")
        fun `selectByUserId should return tenant list by user id`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val userTenant1 = UserTenantEntity().apply {
                userId = testUserId
                tenantId = testTenantId
                role = "admin"
                status = 1
                joinedAt = now
            }
            userTenantMapper.insert(userTenant1)

            // Create second tenant
            val tenant2 = TenantEntity().apply {
                name = "Second Test Tenant"
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }
            tenantMapper.insert(tenant2)

            val userTenant2 = UserTenantEntity().apply {
                userId = testUserId
                tenantId = tenant2.id
                role = "member"
                status = 1
                joinedAt = now
            }
            userTenantMapper.insert(userTenant2)

            // When
            val userTenants = userTenantMapper.selectByUserId(testUserId)

            // Then
            assertTrue(userTenants.isNotEmpty())
            assertEquals(2, userTenants.size)
            userTenants.forEach {
                assertEquals(testUserId, it.userId)
            }
        }

        @Test
        @DisplayName("selectByUserId - Return empty list when user has no tenants")
        fun `selectByUserId should return empty list when user has no tenants`() {
            // Given - create new user without tenants
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newUser = SysUser().apply {
                username = "no_tenant_user"
                password = "password123"
                nickname = "No Tenant User"
                email = "notenant@test.com"
                phone = "13900139001"
                gender = 1
                status = 1
                isAdmin = 0
                active = 1
                createTime = now
                updateTime = now
            }
            sysUserMapper.insert(newUser)

            // When
            val userTenants = userTenantMapper.selectByUserId(newUser.id)

            // Then
            assertTrue(userTenants.isEmpty())
        }

        @Test
        @DisplayName("selectByTenantId - Return user list by tenant ID")
        fun `selectByTenantId should return user list by tenant id`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val userTenant1 = UserTenantEntity().apply {
                userId = testUserId
                tenantId = testTenantId
                role = "admin"
                status = 1
                joinedAt = now
            }
            userTenantMapper.insert(userTenant1)

            // Create second user
            val user2 = SysUser().apply {
                username = "tenant_user_2"
                password = "password123"
                nickname = "Tenant User 2"
                email = "tenantuser2@test.com"
                phone = "13900139002"
                gender = 2
                status = 1
                isAdmin = 0
                active = 1
                createTime = now
                updateTime = now
            }
            sysUserMapper.insert(user2)

            val userTenant2 = UserTenantEntity().apply {
                userId = user2.id
                tenantId = testTenantId
                role = "member"
                status = 1
                joinedAt = now
            }
            userTenantMapper.insert(userTenant2)

            // When
            val userTenants = userTenantMapper.selectByTenantId(testTenantId)

            // Then
            assertTrue(userTenants.isNotEmpty())
            assertEquals(2, userTenants.size)
            userTenants.forEach {
                assertEquals(testTenantId, it.tenantId)
            }
        }

        @Test
        @DisplayName("selectByTenantId - Return empty list when tenant has no users")
        fun `selectByTenantId should return empty list when tenant has no users`() {
            // Given - create new tenant without users
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newTenant = TenantEntity().apply {
                name = "Empty Tenant"
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }
            tenantMapper.insert(newTenant)

            // When
            val userTenants = userTenantMapper.selectByTenantId(newTenant.id)

            // Then
            assertTrue(userTenants.isEmpty())
        }

        @Test
        @DisplayName("selectByUserIdAndTenantId - Return association by user ID and tenant ID")
        fun `selectByUserIdAndTenantId should return association by user id and tenant id`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val userTenant = UserTenantEntity().apply {
                userId = testUserId
                tenantId = testTenantId
                role = "admin"
                status = 1
                joinedAt = now
            }
            userTenantMapper.insert(userTenant)

            // When
            val result = userTenantMapper.selectByUserIdAndTenantId(testUserId, testTenantId)

            // Then
            assertNotNull(result)
            assertEquals(testUserId, result.userId)
            assertEquals(testTenantId, result.tenantId)
            assertEquals("admin", result.role)
            assertEquals(1, result.status)
        }

        @Test
        @DisplayName("selectByUserIdAndTenantId - Return null when association not exists")
        fun `selectByUserIdAndTenantId should return null when association not exists`() {
            // When
            val result = userTenantMapper.selectByUserIdAndTenantId(testUserId, testTenantId)

            // Then
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("Delete Tests")
    inner class DeleteTests {

        @Test
        @DisplayName("deleteByUserIdAndTenantId - Delete association by user ID and tenant ID")
        fun `deleteByUserIdAndTenantId should delete association`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val userTenant = UserTenantEntity().apply {
                userId = testUserId
                tenantId = testTenantId
                role = "member"
                status = 1
                joinedAt = now
            }
            userTenantMapper.insert(userTenant)

            val beforeDelete = userTenantMapper.selectByUserIdAndTenantId(testUserId, testTenantId)
            assertNotNull(beforeDelete)

            // When
            val result = userTenantMapper.deleteByUserIdAndTenantId(testUserId, testTenantId)

            // Then
            assertEquals(1, result)
            val afterDelete = userTenantMapper.selectByUserIdAndTenantId(testUserId, testTenantId)
            assertNull(afterDelete)
        }

        @Test
        @DisplayName("deleteByUserIdAndTenantId - Return 0 when association not exists")
        fun `deleteByUserIdAndTenantId should return 0 when association not exists`() {
            // When
            val result = userTenantMapper.deleteByUserIdAndTenantId(testUserId, testTenantId)

            // Then
            assertEquals(0, result)
        }

        @Test
        @DisplayName("deleteByTenantId - Delete all associations by tenant ID")
        fun `deleteByTenantId should delete all associations by tenant id`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

            // Create multiple users
            val user1 = SysUser().apply {
                username = "delete_test_user_1"
                password = "password123"
                nickname = "Delete Test User 1"
                email = "deletetest1@test.com"
                phone = "13900139010"
                gender = 1
                status = 1
                isAdmin = 0
                active = 1
                createTime = now
                updateTime = now
            }
            sysUserMapper.insert(user1)

            val user2 = SysUser().apply {
                username = "delete_test_user_2"
                password = "password123"
                nickname = "Delete Test User 2"
                email = "deletetest2@test.com"
                phone = "13900139011"
                gender = 2
                status = 1
                isAdmin = 0
                active = 1
                createTime = now
                updateTime = now
            }
            sysUserMapper.insert(user2)

            // Create associations
            val userTenant1 = UserTenantEntity().apply {
                userId = user1.id
                tenantId = testTenantId
                role = "admin"
                status = 1
                joinedAt = now
            }
            userTenantMapper.insert(userTenant1)

            val userTenant2 = UserTenantEntity().apply {
                userId = user2.id
                tenantId = testTenantId
                role = "member"
                status = 1
                joinedAt = now
            }
            userTenantMapper.insert(userTenant2)

            val beforeDelete = userTenantMapper.selectByTenantId(testTenantId)
            assertEquals(2, beforeDelete.size)

            // When
            val result = userTenantMapper.deleteByTenantId(testTenantId)

            // Then
            assertTrue(result >= 2)
            val afterDelete = userTenantMapper.selectByTenantId(testTenantId)
            assertTrue(afterDelete.isEmpty())
        }
    }

    @Nested
    @DisplayName("Update Tests")
    inner class UpdateTests {

        @Test
        @DisplayName("updateRole - Update user role in tenant")
        fun `updateRole should update user role`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val userTenant = UserTenantEntity().apply {
                userId = testUserId
                tenantId = testTenantId
                role = "member"
                status = 1
                joinedAt = now
            }
            userTenantMapper.insert(userTenant)

            val beforeUpdate = userTenantMapper.selectByUserIdAndTenantId(testUserId, testTenantId)
            assertNotNull(beforeUpdate)
            assertEquals("member", beforeUpdate.role)

            // When
            val result = userTenantMapper.updateRole(testUserId, testTenantId, "admin")

            // Then
            assertEquals(1, result)
            val afterUpdate = userTenantMapper.selectByUserIdAndTenantId(testUserId, testTenantId)
            assertNotNull(afterUpdate)
            assertEquals("admin", afterUpdate.role)
        }

        @Test
        @DisplayName("updateRole - Return 0 when association not exists")
        fun `updateRole should return 0 when association not exists`() {
            // When
            val result = userTenantMapper.updateRole(testUserId, testTenantId, "admin")

            // Then
            assertEquals(0, result)
        }
    }
}
