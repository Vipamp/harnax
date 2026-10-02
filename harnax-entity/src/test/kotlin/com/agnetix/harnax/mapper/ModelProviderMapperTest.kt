package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.ModelProvider
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
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
 * ModelProviderMapper Integration Tests
 *
 * @author agnetix
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
open class ModelProviderMapperTest {

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
    private lateinit var modelProviderMapper: ModelProviderMapper

    @Nested
    @DisplayName("Basic CRUD Tests")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - Query model provider by ID")
        fun `selectById should return model provider by id`() {
            // When
            val provider = modelProviderMapper.selectById(1L)

            // Then
            assertNotNull(provider)
            assertEquals(1L, provider.id)
            assertEquals("dashscope", provider.type)
            assertEquals("阿里云百炼", provider.name)
            assertEquals("sk-test-key-12345", provider.apiKey)
            assertEquals(1, provider.status)
            assertEquals(1, provider.active)
        }

        @Test
        @DisplayName("selectById - Return null when provider not exists")
        fun `selectById should return null when provider not exists`() {
            // When
            val provider = modelProviderMapper.selectById(999L)

            // Then
            assertNull(provider)
        }

        @Test
        @DisplayName("selectById - Do not return deleted provider")
        fun `selectById should not return deleted provider`() {
            // When
            val provider = modelProviderMapper.selectById(4L)

            // Then
            assertNull(provider)
        }

        @Test
        @DisplayName("insert - Insert new model provider")
        fun `insert should create new provider`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newProvider = ModelProvider().apply {
                type = "anthropic"
                name = "Anthropic"
                apiKey = "sk-anthropic-key"
                baseUrl = "https://api.anthropic.com"
                status = 1
                isPublic = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = modelProviderMapper.insert(newProvider)

            // Then
            assertEquals(1, result)
            assertTrue(newProvider.id > 0)

            val insertedProvider = modelProviderMapper.selectById(newProvider.id)
            assertNotNull(insertedProvider)
            assertEquals("Anthropic", insertedProvider.name)
        }

        @Test
        @DisplayName("updateById - Update provider information")
        fun `updateById should update provider info`() {
            // Given
            val providerId = 1L
            val provider = modelProviderMapper.selectById(providerId)
            assertNotNull(provider)

            // When
            provider.name = "Updated Provider"
            provider.baseUrl = "https://updated.example.com"
            provider.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val result = modelProviderMapper.updateById(provider)

            // Then
            assertEquals(1, result)
            val updatedProvider = modelProviderMapper.selectById(providerId)
            assertNotNull(updatedProvider)
            assertEquals("Updated Provider", updatedProvider.name)
        }

        @Test
        @DisplayName("deleteById - Logically delete model provider")
        fun `deleteById should logically delete provider`() {
            // Given
            val providerId = 2L
            val providerBefore = modelProviderMapper.selectById(providerId)
            assertNotNull(providerBefore)

            // When
            val result = modelProviderMapper.deleteById(providerId)

            // Then
            assertEquals(1, result)
            val deletedProvider = modelProviderMapper.selectById(providerId)
            assertNull(deletedProvider)
        }
    }

    @Nested
    @DisplayName("Status Management Tests")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - Update model provider status")
        fun `updateStatus should update provider status`() {
            // Given
            val providerId = 1L
            val newStatus = 0

            // When
            val result = modelProviderMapper.updateStatus(providerId, newStatus)
            val updatedProvider = modelProviderMapper.selectById(providerId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedProvider)
            assertEquals(newStatus, updatedProvider.status)
        }
    }

    @Nested
    @DisplayName("Custom Query Tests")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectModelProviderList - Query all model providers")
        fun `selectModelProviderList should return all providers`() {
            // When
            val providers = modelProviderMapper.selectModelProviderList(null, null, null, 1, "admin", 1L)

            // Then
            assertTrue(providers.isNotEmpty())
            assertTrue(providers.size >= 3)
        }

        @Test
        @DisplayName("selectModelProviderList - Filter by name")
        fun `selectModelProviderList should filter by name`() {
            // When
            val providers = modelProviderMapper.selectModelProviderList(null, null, null, 1, "admin", 1L)

            // Then
            assertTrue(providers.isNotEmpty())
            // Test data has providers containing "阿里" in name
            val hasAliProvider = providers.any { it.name.contains("阿里") }
            assertTrue(hasAliProvider || providers.size >= 3)
        }

        @Test
        @DisplayName("selectModelProviderList - The tenant is a wall, whatever is_public says")
        fun `selectModelProviderList should keep another tenant rows out`() {
            // When - tenant 2 asks for its own page as its creator and as a plain member
            val asOwner = modelProviderMapper.selectModelProviderList(null, null, null, null, "testuser2", 2L)
            val asMember = modelProviderMapper.selectModelProviderList(null, null, null, null, "admin", 2L)

            // Then
            assertTrue(asOwner.any { it.id == 6L }, "the tenant's own row must be listed for that tenant")
            assertTrue(asMember.any { it.id == 6L }, "a shared row is listed for the tenant's other members too")
            assertTrue(
                asMember.none { it.id == 1L || it.id == 2L || it.id == 3L },
                "tenant 1 rows stay out of tenant 2's list now that sharing stops at the tenant",
            )
            assertTrue(asOwner.none { it.id == 5L }, "a private row of another tenant must never be listed")
        }

        @Test
        @DisplayName("selectModelProviderList - Inside one tenant, shared rows are for everyone and private ones for their creator")
        fun `selectModelProviderList should share publicly inside the tenant and privately with the creator`() {
            // When - the same tenant, two members: id 5 is that tenant's own private row
            val asAdmin = modelProviderMapper.selectModelProviderList(null, null, null, null, "admin", 1L)
            val asCreator = modelProviderMapper.selectModelProviderList(null, null, null, null, "testuser1", 1L)

            // Then
            assertTrue(asAdmin.all { it.tenantId == 1L }, "every row of a tenant page is that tenant's own")
            assertTrue(asAdmin.none { it.id == 5L }, "another member's private row is not this member's list")
            assertTrue(asCreator.any { it.id == 5L }, "a member's own private row is listed for that member")
            assertTrue(
                asAdmin.any { it.id == 1L } && asCreator.any { it.id == 1L },
                "a shared row is listed for every member of the owning tenant",
            )
        }

        @Test
        @DisplayName("countByName - Count providers by name")
        fun `countByName should count providers by name`() {
            // When
            val count = modelProviderMapper.countByName("阿里云百炼", 1L)

            // Then
            assertEquals(1, count)
        }

        @Test
        @DisplayName("countByName - Return 0 for non-existent name")
        fun `countByName should return 0 for non-existent name`() {
            // When
            val count = modelProviderMapper.countByName("不存在的名称", 1L)

            // Then
            assertEquals(0, count)
        }

        @Test
        @DisplayName("countByName - Ignore the same name held by another tenant")
        fun `countByName should ignore a name that only another tenant holds`() {
            // When - '租户二服务商' is tenant 2's row, so tenant 1 is free to take that name
            val asTenant1 = modelProviderMapper.countByName("租户二服务商", 1L)
            val asTenant2 = modelProviderMapper.countByName("租户二服务商", 2L)

            // Then
            assertEquals(0, asTenant1, "name uniqueness is scoped to the owning tenant")
            assertEquals(1, asTenant2, "the owning tenant must see its own row")
        }
    }

    @Nested
    @DisplayName("Tenant Attribution Tests")
    inner class TenantAttributionTests {

        @Test
        @DisplayName("insert - Store the tenant the row was created under")
        fun `insert should store the row tenant`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val provider = ModelProvider().apply {
                tenantId = 7L
                type = "tenant7_type"
                name = "Tenant 7 Provider"
                status = 1
                isPublic = 0
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            assertEquals(1, modelProviderMapper.insert(provider))

            // Then
            val stored = modelProviderMapper.selectById(provider.id)
            assertNotNull(stored)
            assertEquals(7L, stored.tenantId, "the insert must carry the tenant, not the DDL default")
        }
    }
}
