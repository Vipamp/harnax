package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.ModelProviderCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelProviderResponse
import com.vipamp.vipclaw.admin.dto.ModelProviderUpdateRequest
import com.vipamp.vipclaw.admin.entity.ModelProvider
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.ModelProviderMapper
import com.vipamp.vipclaw.common.page.Page
import org.junit.jupiter.api.*
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
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
        @DisplayName("page - 正常分页查询")
        fun `page should return paginated results`() {
            // Given
            val page = Page<ModelProvider>(1, 2)
            
            // When
            val result = modelProviderService.page(page, null, null)

            // Then
            assertNotNull(result)
            assertTrue(result.total >= 2) // schema-test.sql 中有3条，但deleted的active=0
            assertEquals(2, result.size)
            assertEquals(1, result.current)
            assertEquals(2, result.records.size)
        }

        @Test
        @DisplayName("page - 名称搜索")
        fun `page should filter by name`() {
            // Given
            val page = Page<ModelProvider>(1, 10)
            
            // When
            val result = modelProviderService.page(page, "OpenAI", null)

            // Then
            assertNotNull(result)
            assertTrue(result.total >= 1)
            assertTrue(result.records.all { it.name?.contains("OpenAI") == true })
        }

        @Test
        @DisplayName("page - 状态过滤")
        fun `page should filter by status`() {
            // Given - 创建一个status=1的测试服务商
            val createRequest = ModelProviderCreateRequest(
                name = "StatusTestProvider_${System.currentTimeMillis()}",
                displayName = "Status Test Provider",
                baseUrl = "https://api.statustest.com",
                apiKey = "test-status-key",
                status = 1
            )
            modelProviderService.create(createRequest)
            
            // When
            val page = Page<ModelProvider>(1, 10)
            val result = modelProviderService.page(page, null, 1)

            // Then
            assertNotNull(result)
            assertTrue(result.total >= 1, "应该至少有一个status=1的服务商")
            assertTrue(result.records.all { it.status == 1 }, "所有返回的服务商status都应该是1")
        }
    }

    @Nested
    @DisplayName("查询服务商详情测试")
    inner class GetModelProviderByIdTests {

        @Test
        @DisplayName("getDetail - 查询存在的服务商")
        fun `getDetail should return provider when exists`() {
            // When
            val provider = modelProviderService.getDetail(1L)

            // Then
            assertNotNull(provider)
            assertEquals(1L, provider.id)
            assertEquals("OpenAI", provider.name)
        }

        @Test
        @DisplayName("getDetail - 查询不存在的服务商应该抛出异常")
        fun `getDetail should throw BizException when provider not found`() {
            // When & Then
            assertThrows<BizException> {
                modelProviderService.getDetail(999L)
            }
        }
    }

    @Nested
    @DisplayName("创建服务商测试")
    inner class CreateModelProviderTests {

        @Test
        @DisplayName("create - 创建成功")
        fun `create should create provider successfully`() {
            // Given
            val request = ModelProviderCreateRequest(
                name = "Google",
                displayName = "Google AI",
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = "test-google-key",
                status = 1
            )

            // When
            val result = modelProviderService.create(request)

            // Then
            assertNotNull(result)
            assertEquals("Google", result.name)

            // 验证服务商可以查询到
            val page = Page<ModelProvider>(1, 10)
            val queryResult = modelProviderService.page(page, "Google", null)
            assertTrue(queryResult.total >= 1)
        }
    }

    @Nested
    @DisplayName("更新服务商测试")
    inner class UpdateModelProviderTests {

        @Test
        @DisplayName("update - 更新部分字段")
        fun `update should update partial fields`() {
            // Given
            val request = ModelProviderUpdateRequest(
                name = "OpenAI Updated",
                displayName = "OpenAI (Updated)"
            )

            // When
            val result = modelProviderService.update(1L, request)

            // Then
            assertNotNull(result)
            assertEquals("OpenAI Updated", result.name)
            assertEquals("OpenAI (Updated)", result.displayName)
        }

        @Test
        @DisplayName("update - 更新状态")
        fun `update should update status`() {
            // Given
            val request = ModelProviderUpdateRequest(
                status = 0
            )

            // When
            val result = modelProviderService.update(2L, request)

            // Then
            assertNotNull(result)
            assertEquals(0, result.status)
        }

        @Test
        @DisplayName("update - 服务商不存在应该抛出异常")
        fun `update should throw BizException when provider not found`() {
            // Given
            val request = ModelProviderUpdateRequest(
                name = "New Name"
            )

            // When & Then
            assertThrows<BizException> {
                modelProviderService.update(999L, request)
            }
        }
    }

    @Nested
    @DisplayName("切换服务商状态测试")
    inner class ToggleModelProviderStatusTests {

        @Test
        @DisplayName("toggle - 切换状态")
        fun `toggle should toggle provider status`() {
            // Given - 当前状态是 1
            val before = modelProviderService.getDetail(1L)
            assertEquals(1, before.status)

            // When
            val result = modelProviderService.toggle(1L)

            // Then
            assertNotNull(result)
            assertEquals(0, result.status) // 切换后应该变成0
        }

        @Test
        @DisplayName("toggle - 服务商不存在应该抛出异常")
        fun `toggle should throw BizException when provider not found`() {
            // When & Then
            assertThrows<BizException> {
                modelProviderService.toggle(999L)
            }
        }
    }

    @Nested
    @DisplayName("删除服务商测试")
    inner class DeleteModelProviderTests {

        @Test
        @DisplayName("removeProviderById - 逻辑删除成功")
        fun `removeProviderById should logically delete provider`() {
            // When
            val result = modelProviderService.removeProviderById(2L)

            // Then
            assertTrue(result)

            val provider = modelProviderMapper.selectById(2L)
            assertEquals(0, provider?.active)
        }

        @Test
        @DisplayName("removeProviderById - 删除不存在的服务商")
        fun `removeProviderById should return false when provider not found`() {
            // When
            val result = modelProviderService.removeProviderById(999L)

            // Then
            assertFalse(result)
        }
    }

    // ModelProviderService没有getAllActiveProviders方法，注释掉这个测试
    // @Nested
    // @DisplayName("查询所有启用服务商测试")
    // inner class GetAllActiveProvidersTests { ... }

    @Nested
    @DisplayName("完整业务流程测试")
    inner class BusinessFlowTests {

        @Test
        @DisplayName("完整流程：创建 - 查询 - 更新 - 切换状态 - 删除")
        fun `complete flow create query update toggle delete`() {
            // 1. 创建服务商
            val createRequest = ModelProviderCreateRequest(
                name = "FlowTest Provider",
                displayName = "FlowTest AI",
                baseUrl = "https://api.flowtest.com",
                apiKey = "test-flowtest-key",
                status = 1
            )
            val created = modelProviderService.create(createRequest)
            assertNotNull(created)
            val providerId = created.id ?: throw IllegalStateException("Created provider ID should not be null")

            // 2. 查询服务商
            val provider = modelProviderService.getDetail(providerId)
            assertNotNull(provider)
            assertEquals("FlowTest Provider", provider.name)

            // 3. 更新服务商
            val updateRequest = ModelProviderUpdateRequest(
                displayName = "更新后的FlowTest",
                status = 1
            )
            val updated = modelProviderService.update(providerId, updateRequest)
            assertEquals("更新后的FlowTest", updated.displayName)

            // 4. 切换状态
            val toggled = modelProviderService.toggle(providerId)
            assertEquals(0, toggled.status) // 从1切换到0

            // 5. 删除服务商
            assertTrue(modelProviderService.removeProviderById(providerId))
            val deletedProvider = modelProviderMapper.selectById(providerId)
            assertEquals(0, deletedProvider?.active)
        }
    }
}
