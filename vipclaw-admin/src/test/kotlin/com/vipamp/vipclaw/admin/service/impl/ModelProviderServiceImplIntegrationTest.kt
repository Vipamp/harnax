package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.ModelProviderCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelProviderUpdateRequest
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.ModelProviderMapper
import org.junit.jupiter.api.*
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * ModelProviderServiceImpl 集成测试
 * 使用 Testcontainers 启动真实的 MySQL 容器进行测试
 *
 * @author vipamp
 * @since 2026-04-20
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ModelProviderServiceImplIntegrationTest {

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
    private lateinit var modelProviderService: ModelProviderServiceImpl

    @Autowired
    private lateinit var modelProviderMapper: ModelProviderMapper

    @Nested
    @DisplayName("分页查询测试")
    inner class PaginationTests {

        @Test
        @DisplayName("getModelProviderPage - 正常分页查询")
        fun `getModelProviderPage should return paginated results`() {
            // When
            val page = modelProviderService.getModelProviderPage(null, null, 1, 2)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 2) // schema-test.sql 中有3条，但deleted的active=0
            assertEquals(2, page.size)
            assertEquals(1, page.current)
            assertEquals(2, page.records.size)
        }

        @Test
        @DisplayName("getModelProviderPage - 名称搜索")
        fun `getModelProviderPage should filter by name`() {
            // When
            val page = modelProviderService.page("OpenAI", null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 1)
            assertTrue(page.records.all { it.name.contains("OpenAI") })
        }

        @Test
        @DisplayName("getModelProviderPage - 状态过滤")
        fun `getModelProviderPage should filter by status`() {
            // When
            val page = modelProviderService.getModelProviderPage(null, 1, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 2)
            assertTrue(page.records.all { it.status == 1 })
        }
    }

    @Nested
    @DisplayName("查询服务商详情测试")
    inner class GetModelProviderByIdTests {

        @Test
        @DisplayName("getModelProviderById - 查询存在的服务商")
        fun `getModelProviderById should return provider when exists`() {
            // When
            val provider = modelProviderService.getModelProviderById(1L)

            // Then
            assertNotNull(provider)
            assertEquals(1L, provider?.id)
            assertEquals("OpenAI", provider?.name)
        }

        @Test
        @DisplayName("getModelProviderById - 查询不存在的服务商应该抛出异常")
        fun `getModelProviderById should throw BizException when provider not found`() {
            // When & Then
            assertThrows<BizException> {
                modelProviderService.getModelProviderById(999L)
            }
        }
    }

    @Nested
    @DisplayName("创建服务商测试")
    inner class CreateModelProviderTests {

        @Test
        @DisplayName("createModelProvider - 创建成功")
        fun `createModelProvider should create provider successfully`() {
            // Given
            val request = ModelProviderCreateRequest(
                name = "Google",
                displayName = "Google AI",
                apiBaseUrl = "https://generativelanguage.googleapis.com",
                apiKey = "test-google-key",
                status = 1
            )

            // When
            val result = modelProviderService.createModelProvider(request)

            // Then
            assertTrue(result)

            // 验证服务商可以查询到
            val page = modelProviderService.getModelProviderPage("Google", null, 1, 10)
            assertTrue(page.total >= 1)
        }
    }

    @Nested
    @DisplayName("更新服务商测试")
    inner class UpdateModelProviderTests {

        @Test
        @DisplayName("updateModelProvider - 更新部分字段")
        fun `updateModelProvider should update partial fields`() {
            // Given
            val request = ModelProviderUpdateRequest(
                name = "OpenAI Updated",
                displayName = "OpenAI (Updated)"
            )

            // When
            val result = modelProviderService.updateModelProvider(1L, request)

            // Then
            assertTrue(result)

            // 验证更新成功
            val provider = modelProviderMapper.selectById(1L)
            assertEquals("OpenAI Updated", provider?.name)
            assertEquals("OpenAI (Updated)", provider?.displayName)
        }

        @Test
        @DisplayName("updateModelProvider - 更新状态")
        fun `updateModelProvider should update status`() {
            // Given
            val request = ModelProviderUpdateRequest(
                status = 0
            )

            // When
            val result = modelProviderService.updateModelProvider(2L, request)

            // Then
            assertTrue(result)

            val provider = modelProviderMapper.selectById(2L)
            assertEquals(0, provider?.status)
        }

        @Test
        @DisplayName("updateModelProvider - 服务商不存在应该抛出异常")
        fun `updateModelProvider should throw BizException when provider not found`() {
            // Given
            val request = ModelProviderUpdateRequest(
                name = "New Name"
            )

            // When & Then
            assertThrows<BizException> {
                modelProviderService.updateModelProvider(999L, request)
            }
        }
    }

    @Nested
    @DisplayName("切换服务商状态测试")
    inner class ToggleModelProviderStatusTests {

        @Test
        @DisplayName("toggleModelProviderStatus - 禁用服务商")
        fun `toggleModelProviderStatus should disable provider`() {
            // When
            val result = modelProviderService.toggleModelProviderStatus(1L, 0)

            // Then
            assertTrue(result)

            val provider = modelProviderMapper.selectById(1L)
            assertEquals(0, provider?.status)
        }

        @Test
        @DisplayName("toggleModelProviderStatus - 启用服务商")
        fun `toggleModelProviderStatus should enable provider`() {
            // Given
            modelProviderService.toggleModelProviderStatus(2L, 0)

            // When
            val result = modelProviderService.toggleModelProviderStatus(2L, 1)

            // Then
            assertTrue(result)

            val provider = modelProviderMapper.selectById(2L)
            assertEquals(1, provider?.status)
        }

        @Test
        @DisplayName("toggleModelProviderStatus - 服务商不存在应该抛出异常")
        fun `toggleModelProviderStatus should throw BizException when provider not found`() {
            // When & Then
            assertThrows<BizException> {
                modelProviderService.toggleModelProviderStatus(999L, 1)
            }
        }
    }

    @Nested
    @DisplayName("删除服务商测试")
    inner class DeleteModelProviderTests {

        @Test
        @DisplayName("deleteModelProvider - 逻辑删除成功")
        fun `deleteModelProvider should logically delete provider`() {
            // When
            val result = modelProviderService.deleteModelProvider(2L)

            // Then
            assertTrue(result)

            val provider = modelProviderMapper.selectById(2L)
            assertEquals(0, provider?.active)
        }

        @Test
        @DisplayName("deleteModelProvider - 删除不存在的服务商应该抛出异常")
        fun `deleteModelProvider should throw BizException when provider not found`() {
            // When & Then
            assertThrows<BizException> {
                modelProviderService.deleteModelProvider(999L)
            }
        }
    }

    @Nested
    @DisplayName("查询所有启用服务商测试")
    inner class GetAllActiveProvidersTests {

        @Test
        @DisplayName("getAllActiveProviders - 查询所有启用的服务商")
        fun `getAllActiveProviders should return all active providers`() {
            // When
            val providers = modelProviderService.getAllActiveProviders()

            // Then
            assertNotNull(providers)
            assertTrue(providers.size >= 2)
            assertTrue(providers.all { it.status == 1 && it.active == 1 })
        }
    }

    @Nested
    @DisplayName("完整业务流程测试")
    inner class BusinessFlowTests {

        @Test
        @DisplayName("完整流程：创建 - 查询 - 更新 - 禁用 - 删除")
        fun `complete flow create query update disable delete`() {
            // 1. 创建服务商
            val createRequest = ModelProviderCreateRequest(
                name = "FlowTest Provider",
                displayName = "FlowTest AI",
                apiBaseUrl = "https://api.flowtest.com",
                apiKey = "test-flowtest-key",
                status = 1
            )
            assertTrue(modelProviderService.createModelProvider(createRequest))

            // 2. 查询服务商
            val page = modelProviderService.getModelProviderPage("FlowTest Provider", null, 1, 10)
            assertTrue(page.total >= 1)
            val providerId = page.records[0].id

            // 3. 更新服务商
            val updateRequest = ModelProviderUpdateRequest(
                displayName = "更新后的FlowTest",
                status = 1
            )
            assertTrue(modelProviderService.updateModelProvider(providerId, updateRequest))

            val updatedProvider = modelProviderService.getModelProviderById(providerId)
            assertEquals("更新后的FlowTest", updatedProvider.displayName)

            // 4. 禁用服务商
            assertTrue(modelProviderService.toggleModelProviderStatus(providerId, 0))
            val disabledProvider = modelProviderMapper.selectById(providerId)
            assertEquals(0, disabledProvider?.status)

            // 5. 删除服务商
            assertTrue(modelProviderService.deleteModelProvider(providerId))
            val deletedProvider = modelProviderMapper.selectById(providerId)
            assertEquals(0, deletedProvider?.active)
        }
    }
}
