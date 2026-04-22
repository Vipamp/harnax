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
 * 使用 Testcontainers 启动真实的 MySQL 容器测试 MyBatis SQL 映射
 *
 * @author vipamp
 * @since 2026-04-23
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
        @DisplayName("selectById - 根据 ID 查询供应商")
        fun `selectById should return provider by id`() {
            // When
            val provider = modelProviderMapper.selectById(1L)

            // Then
            assertNotNull(provider)
            assertEquals(1L, provider.id)
            assertEquals("dashscope", provider.name)
            assertEquals("阿里云百炼", provider.displayName)
            assertEquals("sk-test-key-12345", provider.apiKey)
            assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", provider.baseUrl)
            assertEquals(1, provider.status)
            assertEquals(1, provider.isPublic)
            assertEquals("admin", provider.creator)
            assertEquals(1, provider.active)
            assertNotNull(provider.createTime)
            assertNotNull(provider.updateTime)
        }

        @Test
        @DisplayName("selectById - 查询不存在的供应商返回 null")
        fun `selectById should return null when provider not exists`() {
            // When
            val provider = modelProviderMapper.selectById(999L)

            // Then
            assertNull(provider)
        }

        @Test
        @DisplayName("insert - 插入新供应商")
        fun `insert should create new provider`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newProvider = ModelProvider().apply {
                name = "anthropic"  // 使用测试数据中不存在的名称
                displayName = "Anthropic Claude"
                apiKey = "sk-anthropic-key-99999"
                baseUrl = "https://api.anthropic.com/v1"
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
            assertNotNull(newProvider.id)
            assertTrue(newProvider.id > 0)

            // 验证供应商可以查询到且所有字段正确
            val savedProvider = modelProviderMapper.selectById(newProvider.id)
            assertNotNull(savedProvider)
            assertEquals(newProvider.id, savedProvider.id)
            assertEquals("anthropic", savedProvider.name)
            assertEquals("Anthropic Claude", savedProvider.displayName)
            assertEquals("sk-anthropic-key-99999", savedProvider.apiKey)
            assertEquals("https://api.anthropic.com/v1", savedProvider.baseUrl)
            assertEquals(1, savedProvider.status)
            assertEquals(1, savedProvider.isPublic)
            assertEquals("admin", savedProvider.creator)
            assertEquals(1, savedProvider.active)
            assertEquals(now, savedProvider.createTime)
            assertEquals(now, savedProvider.updateTime)
        }

        @Test
        @DisplayName("updateById - 更新供应商信息")
        fun `updateById should update provider`() {
            // Given
            val provider = modelProviderMapper.selectById(1L)
            assertNotNull(provider)

            // 记录原始值
            val originalName = provider.name
            val originalDisplayName = provider.displayName

            // 修改字段
            provider.displayName = "阿里云百炼（更新）"
            provider.baseUrl = "https://new-dashscope-url.com/v1"
            provider.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

            // When
            val result = modelProviderMapper.updateById(provider)

            // Then
            assertEquals(1, result)

            // 验证更新后的数据
            val updatedProvider = modelProviderMapper.selectById(1L)
            assertNotNull(updatedProvider)
            assertEquals(originalName, updatedProvider.name) // name 未修改
            assertEquals("阿里云百炼（更新）", updatedProvider.displayName)
            assertEquals("https://new-dashscope-url.com/v1", updatedProvider.baseUrl)
            assertEquals(provider.updateTime, updatedProvider.updateTime)
        }

        @Test
        @DisplayName("updateById - 更新所有字段")
        fun `updateById should update all fields`() {
            // Given
            val provider = modelProviderMapper.selectById(1L)
            assertNotNull(provider)

            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            provider.name = "anthropic"
            provider.displayName = "Anthropic Claude"
            provider.apiKey = "sk-new-api-key"
            provider.baseUrl = "https://api.anthropic.com/v1"
            provider.status = 0
            provider.isPublic = 0
            provider.creator = "newadmin"
            provider.active = 1
            provider.updateTime = now

            // When
            val result = modelProviderMapper.updateById(provider)

            // Then
            assertEquals(1, result)

            val updatedProvider = modelProviderMapper.selectById(1L)
            assertNotNull(updatedProvider)
            assertEquals("anthropic", updatedProvider.name)
            assertEquals("Anthropic Claude", updatedProvider.displayName)
            assertEquals("sk-new-api-key", updatedProvider.apiKey)
            assertEquals("https://api.anthropic.com/v1", updatedProvider.baseUrl)
            assertEquals(0, updatedProvider.status)
            assertEquals(0, updatedProvider.isPublic)
            assertEquals("newadmin", updatedProvider.creator)
            assertEquals(now, updatedProvider.updateTime)
        }

        @Test
        @DisplayName("deleteById - 逻辑删除供应商")
        fun `deleteById should logically delete provider`() {
            // When
            val result = modelProviderMapper.deleteById(1L)

            // Then
            assertEquals(1, result)

            // selectById 仍能查询到（因为是逻辑删除）
            val deletedProvider = modelProviderMapper.selectById(1L)
            assertNotNull(deletedProvider)
            assertEquals(0, deletedProvider.active) // active 变为 0

            // selectActiveById 无法查询到（因为只查询 active=1）
            val activeProvider = modelProviderMapper.selectActiveById(1L)
            assertNull(activeProvider)
        }
    }

    @Nested
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectActiveById - 查询启用的供应商")
        fun `selectActiveById should return active provider`() {
            // When
            val provider = modelProviderMapper.selectActiveById(1L)

            // Then
            assertNotNull(provider)
            assertEquals(1L, provider.id)
            assertEquals(1, provider.active)
        }

        @Test
        @DisplayName("selectActiveById - 已删除的供应商返回 null")
        fun `selectActiveById should return null for deleted provider`() {
            // Given - 先删除供应商
            modelProviderMapper.deleteById(1L)

            // When
            val provider = modelProviderMapper.selectActiveById(1L)

            // Then
            assertNull(provider)
        }

        @Test
        @DisplayName("countByName - 统计供应商名称数量")
        fun `countByName should count providers by name`() {
            // When & Then
            assertEquals(1, modelProviderMapper.countByName("dashscope"))
            assertEquals(0, modelProviderMapper.countByName("nonexistent"))
        }

        @Test
        @DisplayName("countByName - 不统计已删除的供应商")
        fun `countByName should not count deleted providers`() {
            // Given - 先删除供应商
            modelProviderMapper.deleteById(1L)

            // When
            val count = modelProviderMapper.countByName("dashscope")

            // Then
            assertEquals(0, count)
        }

        @Test
        @DisplayName("selectModelProviderList - 查询所有可见供应商")
        fun `selectModelProviderList should return all visible providers`() {
            // When
            val providers = modelProviderMapper.selectModelProviderList(null, null, "admin")

            // Then
            assertTrue(providers.isNotEmpty())
            // 应该包含公开供应商和当前用户创建的供应商
            assertTrue(providers.any { it.name == "dashscope" })
        }

        @Test
        @DisplayName("selectModelProviderList - 按名称模糊搜索")
        fun `selectModelProviderList should filter by name`() {
            // When
            val providers = modelProviderMapper.selectModelProviderList("dash", null, "admin")

            // Then
            assertEquals(1, providers.size)
            assertEquals("dashscope", providers[0].name)
        }

        @Test
        @DisplayName("selectModelProviderList - 按显示名称模糊搜索")
        fun `selectModelProviderList should filter by displayName`() {
            // When
            val providers = modelProviderMapper.selectModelProviderList("阿里云", null, "admin")

            // Then
            assertEquals(1, providers.size)
            assertEquals("dashscope", providers[0].name)
        }

        @Test
        @DisplayName("selectModelProviderList - 按状态筛选")
        fun `selectModelProviderList should filter by status`() {
            // When - 查询启用的供应商
            val enabledProviders = modelProviderMapper.selectModelProviderList(null, 1, "admin")

            // Then
            assertTrue(enabledProviders.isNotEmpty())
            assertTrue(enabledProviders.all { it.status == 1 })
        }

        @Test
        @DisplayName("selectModelProviderList - 数据权限控制（只看公开和自己的）")
        fun `selectModelProviderList should respect data permission`() {
            // Given - 插入一个私有供应商（其他用户创建）
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val privateProvider = ModelProvider().apply {
                name = "private_provider"
                displayName = "私有供应商"
                apiKey = "sk-private-key"
                baseUrl = "https://private.com/v1"
                status = 1
                isPublic = 0 // 私有
                creator = "other_user" // 其他用户创建
                active = 1
                createTime = now
                updateTime = now
            }
            modelProviderMapper.insert(privateProvider)

            // When - admin 用户查询
            val providers = modelProviderMapper.selectModelProviderList(null, null, "admin")

            // Then - 不应该看到其他用户的私有供应商
            assertTrue(providers.none { it.name == "private_provider" })
            // 但应该能看到公开的供应商
            assertTrue(providers.any { it.name == "dashscope" })
        }

        @Test
        @DisplayName("selectModelProviderList - 创建者可以看到自己的私有供应商")
        fun `selectModelProviderList should allow creator to see own private providers`() {
            // Given - 插入一个私有供应商（admin 创建）
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val privateProvider = ModelProvider().apply {
                name = "admin_private"
                displayName = "Admin 私有供应商"
                apiKey = "sk-private-key"
                baseUrl = "https://private.com/v1"
                status = 1
                isPublic = 0
                creator = "admin" // admin 创建
                active = 1
                createTime = now
                updateTime = now
            }
            modelProviderMapper.insert(privateProvider)

            // When - admin 用户查询
            val providers = modelProviderMapper.selectModelProviderList(null, null, "admin")

            // Then - 应该能看到自己创建的私有供应商
            assertTrue(providers.any { it.name == "admin_private" })
        }

        @Test
        @DisplayName("selectModelProviderList - 组合搜索和筛选")
        fun `selectModelProviderList should support combined search and filter`() {
            // When - 搜索名称包含 "dash" 且状态为启用的供应商
            val providers = modelProviderMapper.selectModelProviderList("dash", 1, "admin")

            // Then
            assertEquals(1, providers.size)
            assertEquals("dashscope", providers[0].name)
            assertEquals(1, providers[0].status)
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("insert - 插入可选字段为 null 的供应商")
        fun `insert should handle null optional fields`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val provider = ModelProvider().apply {
                name = "ollama"
                displayName = "本地模型"
                apiKey = null // 可选字段为 null
                baseUrl = null // 可选字段为 null
                status = 1
                isPublic = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = modelProviderMapper.insert(provider)

            // Then
            assertEquals(1, result)
            val savedProvider = modelProviderMapper.selectById(provider.id)
            assertNotNull(savedProvider)
            assertNull(savedProvider.apiKey)
            assertNull(savedProvider.baseUrl)
        }

        @Test
        @DisplayName("updateById - 更新字段为 null")
        fun `updateById should handle null fields`() {
            // Given
            val provider = modelProviderMapper.selectById(1L)
            assertNotNull(provider)

            provider.apiKey = null
            provider.baseUrl = null
            provider.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

            // When
            val result = modelProviderMapper.updateById(provider)

            // Then
            assertEquals(1, result)
            val updatedProvider = modelProviderMapper.selectById(1L)
            assertNotNull(updatedProvider)
            assertNull(updatedProvider.apiKey)
            assertNull(updatedProvider.baseUrl)
        }

        @Test
        @DisplayName("selectModelProviderList - 空搜索字符串不过滤")
        fun `selectModelProviderList should not filter with empty string`() {
            // When
            val providers = modelProviderMapper.selectModelProviderList("", null, "admin")

            // Then - 空字符串应该不过滤，返回所有结果
            assertTrue(providers.isNotEmpty())
        }
    }
}
