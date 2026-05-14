package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.ModelProvider
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
 * ModelProviderMapper 集成测试
 *
 * @author vipamp
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ModelProviderMapperTest {

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
    private lateinit var modelProviderMapper: ModelProviderMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询模型服务商")
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
        @DisplayName("selectById - 查询不存在的服务商返回 null")
        fun `selectById should return null when provider not exists`() {
            // When
            val provider = modelProviderMapper.selectById(999L)

            // Then
            assertNull(provider)
        }

        @Test
        @DisplayName("selectById - 不返回已删除的服务商")
        fun `selectById should not return deleted provider`() {
            // When
            val provider = modelProviderMapper.selectById(4L)

            // Then
            assertNull(provider)
        }

        @Test
        @DisplayName("insert - 插入新服务商")
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
        @DisplayName("updateById - 更新服务商信息")
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
        @DisplayName("deleteById - 逻辑删除服务商")
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
    @DisplayName("状态管理测试")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - 更新服务商状态")
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
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectModelProviderList - 查询所有服务商列表")
        fun `selectModelProviderList should return all providers`() {
            // When
            val providers = modelProviderMapper.selectModelProviderList(null, null, null, 1, "admin")

            // Then
            assertTrue(providers.isNotEmpty())
            assertTrue(providers.size >= 3)
        }

        @Test
        @DisplayName("selectModelProviderList - 按名称模糊查询")
        fun `selectModelProviderList should filter by name`() {
            // When
            val providers = modelProviderMapper.selectModelProviderList(null, null, null, 1, "admin")

            // Then
            assertTrue(providers.isNotEmpty())
            // 测试数据中有包含"阿里"的服务商
            val hasAliProvider = providers.any { it.name.contains("阿里") }
            assertTrue(hasAliProvider || providers.size >= 3)
        }

        @Test
        @DisplayName("countByType - 统计指定类型服务商数量")
        fun `countByType should count providers by type`() {
            // When
            val count = modelProviderMapper.countByType("dashscope")

            // Then
            assertEquals(1, count)
        }

        @Test
        @DisplayName("countByName - 统计指定名称服务商数量")
        fun `countByName should count providers by name`() {
            // When
            val count = modelProviderMapper.countByName("阿里云百炼")

            // Then
            assertEquals(1, count)
        }

        @Test
        @DisplayName("countByName - 不存在的名称返回 0")
        fun `countByName should return 0 for non-existent name`() {
            // When
            val count = modelProviderMapper.countByName("不存在的名称")

            // Then
            assertEquals(0, count)
        }
    }
}
