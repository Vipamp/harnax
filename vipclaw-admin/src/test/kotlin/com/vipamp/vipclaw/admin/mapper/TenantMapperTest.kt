package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.TenantEntity
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
 * TenantMapper Integration Tests
 * Uses Testcontainers to start a real MySQL container for testing MyBatis SQL mappings
 *
 * @author vipamp
 * @since 2026-05-16
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TenantMapperTest {

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
    private lateinit var tenantMapper: TenantMapper

    @Nested
    @DisplayName("Basic CRUD Tests")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - Return tenant by ID")
        fun `selectById should return tenant by id`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val tenant = TenantEntity().apply {
                name = "Test Tenant"
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }
            tenantMapper.insert(tenant)

            // When
            val result = tenantMapper.selectById(tenant.id)

            // Then
            assertNotNull(result)
            assertEquals(tenant.id, result.id)
            assertEquals("Test Tenant", result.name)
            assertEquals(1, result.status)
            assertEquals("admin", result.creator)
            assertEquals(1, result.active)
        }

        @Test
        @DisplayName("selectById - Return null when tenant not exists")
        fun `selectById should return null when tenant not exists`() {
            // When
            val result = tenantMapper.selectById(999L)

            // Then
            assertNull(result)
        }

        @Test
        @DisplayName("selectById - Not return deleted tenant (active=0)")
        fun `selectById should not return deleted tenant`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val tenant = TenantEntity().apply {
                name = "Deleted Tenant"
                status = 1
                creator = "admin"
                active = 0 // Deleted
                createTime = now
                updateTime = now
            }
            tenantMapper.insert(tenant)

            // When
            val result = tenantMapper.selectById(tenant.id)

            // Then
            assertNull(result)
        }

        @Test
        @DisplayName("insert - Create new tenant")
        fun `insert should create new tenant`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val tenant = TenantEntity().apply {
                name = "New Tenant"
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = tenantMapper.insert(tenant)

            // Then
            assertEquals(1, result)
            assertTrue(tenant.id > 0)

            // Verify can be queried
            val insertedTenant = tenantMapper.selectById(tenant.id)
            assertNotNull(insertedTenant)
            assertEquals("New Tenant", insertedTenant.name)
            assertEquals("admin", insertedTenant.creator)
        }

        @Test
        @DisplayName("updateById - Update tenant info")
        fun `updateById should update tenant info`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val tenant = TenantEntity().apply {
                name = "Update Test Tenant"
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }
            tenantMapper.insert(tenant)

            val tenantBefore = tenantMapper.selectById(tenant.id)
            assertNotNull(tenantBefore)

            // When
            tenantBefore.name = "Updated Tenant Name"
            tenantBefore.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val result = tenantMapper.updateById(tenantBefore)

            // Then
            assertEquals(1, result)
            val tenantAfter = tenantMapper.selectById(tenant.id)
            assertNotNull(tenantAfter)
            assertEquals("Updated Tenant Name", tenantAfter.name)
        }

        @Test
        @DisplayName("deleteById - Logically delete tenant")
        fun `deleteById should logically delete tenant`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val tenant = TenantEntity().apply {
                name = "Delete Test Tenant"
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }
            tenantMapper.insert(tenant)

            val tenantBefore = tenantMapper.selectById(tenant.id)
            assertNotNull(tenantBefore)
            assertEquals(1, tenantBefore.active)

            // When
            val result = tenantMapper.deleteById(tenant.id)

            // Then
            assertEquals(1, result)

            // selectById should not return it (because active=0)
            val deletedTenant = tenantMapper.selectById(tenant.id)
            assertNull(deletedTenant)
        }

        @Test
        @DisplayName("deleteById - Return 0 when tenant not exists")
        fun `deleteById should return 0 when tenant not exists`() {
            // When
            val result = tenantMapper.deleteById(999L)

            // Then
            assertEquals(0, result)
        }

        @Test
        @DisplayName("deleteById - Not delete already deleted tenant")
        fun `deleteById should not delete already deleted tenant`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val tenant = TenantEntity().apply {
                name = "Already Deleted Tenant"
                status = 1
                creator = "admin"
                active = 0
                createTime = now
                updateTime = now
            }
            tenantMapper.insert(tenant)

            // When
            val result = tenantMapper.deleteById(tenant.id)

            // Then
            assertEquals(0, result)
        }
    }

    @Nested
    @DisplayName("Custom Query Tests")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectList - Query all tenants")
        fun `selectList should return all tenants`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val tenant1 = TenantEntity().apply {
                name = "Tenant A"
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }
            val tenant2 = TenantEntity().apply {
                name = "Tenant B"
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }
            tenantMapper.insert(tenant1)
            tenantMapper.insert(tenant2)

            // When
            val tenants = tenantMapper.selectList(null, null)

            // Then
            assertTrue(tenants.isNotEmpty())
            assertTrue(tenants.size >= 2)
        }

        @Test
        @DisplayName("selectList - Filter by name")
        fun `selectList should filter by name`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val tenant = TenantEntity().apply {
                name = "Search Test Tenant"
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }
            tenantMapper.insert(tenant)

            // When
            val tenants = tenantMapper.selectList("Search Test", null)

            // Then
            assertTrue(tenants.isNotEmpty())
            tenants.forEach {
                assertTrue(it.name.contains("Search Test"))
            }
        }

        @Test
        @DisplayName("selectList - Filter by status")
        fun `selectList should filter by status`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val tenant1 = TenantEntity().apply {
                name = "Active Tenant"
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }
            val tenant2 = TenantEntity().apply {
                name = "Inactive Tenant"
                status = 0
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }
            tenantMapper.insert(tenant1)
            tenantMapper.insert(tenant2)

            // When
            val tenants = tenantMapper.selectList(null, 0)

            // Then
            assertTrue(tenants.isNotEmpty())
            tenants.forEach {
                assertEquals(0, it.status)
            }
        }

        @Test
        @DisplayName("selectByName - Return tenant by name")
        fun `selectByName should return tenant by name`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val tenant = TenantEntity().apply {
                name = "Unique Tenant Name"
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }
            tenantMapper.insert(tenant)

            // When
            val result = tenantMapper.selectByName("Unique Tenant Name")

            // Then
            assertNotNull(result)
            assertEquals("Unique Tenant Name", result.name)
        }

        @Test
        @DisplayName("selectByName - Return null when name not exists")
        fun `selectByName should return null when name not exists`() {
            // When
            val result = tenantMapper.selectByName("Nonexistent Tenant")

            // Then
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("Status Management Tests")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - Update tenant status")
        fun `updateStatus should update tenant status`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val tenant = TenantEntity().apply {
                name = "Status Test Tenant"
                status = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }
            tenantMapper.insert(tenant)
            val newStatus = 0

            // When
            val result = tenantMapper.updateStatus(tenant.id, newStatus)
            val updatedTenant = tenantMapper.selectById(tenant.id)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedTenant)
            assertEquals(newStatus, updatedTenant.status)
        }

        @Test
        @DisplayName("updateStatus - Return 0 when tenant not exists")
        fun `updateStatus should return 0 when tenant not exists`() {
            // When
            val result = tenantMapper.updateStatus(999L, 0)

            // Then
            assertEquals(0, result)
        }
    }
}
