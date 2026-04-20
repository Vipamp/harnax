package com.vipamp.vipclaw.admin.service.impl

import com.vipamp.vipclaw.admin.dto.ModelCreateRequest
import com.vipamp.vipclaw.admin.dto.ModelUpdateRequest
import com.vipamp.vipclaw.admin.entity.Model
import com.vipamp.vipclaw.admin.exception.BizException
import com.vipamp.vipclaw.admin.mapper.ModelMapper
import com.vipamp.vipclaw.common.page.Page
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
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
 * ModelServiceImpl 集成测试
 * 使用 Testcontainers 启动真实的 MySQL 容器进行测试
 *
 * @author vipamp
 * @since 2026-04-20
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class ModelServiceImplIntegrationTest {

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
    private lateinit var modelService: ModelServiceImpl

    @Autowired
    private lateinit var modelMapper: ModelMapper

    @Nested
    @DisplayName("分页查询测试")
    inner class PageTests {

        @Test
        @DisplayName("page - 正常分页查询")
        fun `page should return paginated results`() {
            // Given
            val page = Page<Model>(1, 2)

            // When
            val result = modelService.page(page, null, null, null, null, null, null, null)

            // Then
            assertNotNull(result)
            assertTrue(result.total >= 3) // schema-test.sql 中有4条，但deleted的active=0
            assertEquals(2, result.size)
            assertEquals(1, result.current)
            assertEquals(2, result.records.size)
        }

        @Test
        @DisplayName("page - 名称搜索")
        fun `page should filter by name`() {
            // Given
            val page = Page<Model>(1, 10)

            // When
            val result = modelService.page(page, "GPT-4", null, null, null, null, null, null)

            // Then
            assertNotNull(result)
            assertTrue(result.total >= 1)
            assertTrue(result.records.all { it.modelName?.contains("gpt-4") == true || it.name?.contains("GPT-4") == true })
        }

        @Test
        @DisplayName("page - 供应商过滤")
        fun `page should filter by providerId`() {
            // Given
            val page = Page<Model>(1, 10)

            // When
            val result = modelService.page(page, null, 2L, null, null, null, null, null)

            // Then
            assertNotNull(result)
            assertTrue(result.total >= 1)
            assertTrue(result.records.all { it.providerId == 2L })
        }

        @Test
        @DisplayName("page - 状态过滤")
        fun `page should filter by status`() {
            // Given
            val page = Page<Model>(1, 10)

            // When
            val result = modelService.page(page, null, null, null, 0, null, null, null)

            // Then
            assertNotNull(result)
            // 当前测试数据中所有active=1的模型status都是1
        }

        @Test
        @DisplayName("page - 价格范围过滤")
        fun `page should filter by price range`() {
            // Given
            val page = Page<Model>(1, 10)

            // When
            val result = modelService.page(page, null, null, null, null, null, 0.0010, 0.0030)

            // Then
            assertNotNull(result)
            assertTrue(result.records.all { 
                val p = it.price
                p != null && p >= 0.0010 && p <= 0.0030 
            })
        }
    }

    @Nested
    @DisplayName("查询模型详情测试")
    inner class GetDetailTests {

        @Test
        @DisplayName("getDetail - 查询存在的模型")
        fun `getDetail should return model when exists`() {
            // When
            val result = modelService.getDetail(1L)

            // Then
            assertNotNull(result)
            assertEquals(1L, result.id)
            assertEquals("gpt-4", result.modelName)
        }

        @Test
        @DisplayName("getDetail - 查询不存在的模型应该抛出异常")
        fun `getDetail should throw BizException when model not found`() {
            // When & Then
            assertThrows<BizException> {
                modelService.getDetail(999L)
            }
        }
    }

    @Nested
    @DisplayName("创建模型测试")
    inner class CreateModelTests {

        @Test
        @DisplayName("create - 创建成功")
        fun `create should create model successfully`() {
            // Given
            val request = ModelCreateRequest(
                name = "New Model",
                modelName = "new-model-v1",
                providerId = 1L,
                description = "新模型",
                modelType = "chat",
                supportInternet = 0,
                supportReasoning = 1,
                supportTool = 1,
                supportMcp = 0,
                supportVision = 0,
                price = 0.0050,
                status = 1
            )

            // When
            val result = modelService.create(request)

            // Then
            assertNotNull(result)
            assertEquals("new-model-v1", result.modelName)
            assertEquals("新模型", result.description)
        }

        @Test
        @DisplayName("create - 创建最小化模型")
        fun `create should create minimal model`() {
            // Given
            val request = ModelCreateRequest(
                modelName = "minimal-model",
                providerId = 1L,
                modelType = "chat"
            )

            // When
            val result = modelService.create(request)

            // Then
            assertNotNull(result)
            assertEquals("minimal-model", result.modelName)
        }
    }

    @Nested
    @DisplayName("更新模型测试")
    inner class UpdateModelTests {

        @Test
        @DisplayName("update - 更新部分字段")
        fun `update should update partial fields`() {
            // Given
            val request = ModelUpdateRequest(
                name = "Updated GPT-4",
                description = "更新后的描述",
                price = 0.0350
            )

            // When
            val result = modelService.update(1L, request)

            // Then
            assertNotNull(result)
            assertEquals("Updated GPT-4", result.name)
            assertEquals("更新后的描述", result.description)
            assertEquals(0.0350, result.price)
        }

        @Test
        @DisplayName("update - 更新状态")
        fun `update should update status`() {
            // Given
            val request = ModelUpdateRequest(
                status = 0
            )

            // When
            val result = modelService.update(2L, request)

            // Then
            assertNotNull(result)
            assertEquals(0, result.status)
        }

        @Test
        @DisplayName("update - 模型不存在应该抛出异常")
        fun `update should throw BizException when model not found`() {
            // Given
            val request = ModelUpdateRequest(
                name = "New Name"
            )

            // When & Then
            assertThrows<BizException> {
                modelService.update(999L, request)
            }
        }
    }

    @Nested
    @DisplayName("切换模型状态测试")
    inner class ToggleTests {

        @Test
        @DisplayName("toggle - 切换状态")
        fun `toggle should toggle model status`() {
            // Given - 当前状态是 1
            val before = modelService.getDetail(3L)
            assertEquals(1, before.status)

            // When
            val result = modelService.toggle(3L)

            // Then
            assertNotNull(result)
            assertEquals(0, result.status)

            // 再次切换
            val result2 = modelService.toggle(3L)
            assertEquals(1, result2.status)
        }

        @Test
        @DisplayName("toggle - 模型不存在应该抛出异常")
        fun `toggle should throw BizException when model not found`() {
            // When & Then
            assertThrows<BizException> {
                modelService.toggle(999L)
            }
        }
    }

    @Nested
    @DisplayName("根据ID获取模型实体测试")
    inner class GetModelByIdTests {

        @Test
        @DisplayName("getModelById - 查询存在的模型")
        fun `getModelById should return model when exists`() {
            // When
            val result = modelService.getModelById(1L)

            // Then
            assertNotNull(result)
            assertEquals(1L, result?.id)
            assertEquals("gpt-4", result?.modelName)
        }

        @Test
        @DisplayName("getModelById - 查询不存在的模型返回null")
        fun `getModelById should return null when model not found`() {
            // When
            val result = modelService.getModelById(999L)

            // Then
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("删除模型测试")
    inner class DeleteByIdTests {

        @Test
        @DisplayName("deleteById - 逻辑删除成功")
        fun `deleteById should logically delete model`() {
            // When
            modelService.deleteById(2L)

            // Then
            val model = modelMapper.selectById(2L)
            assertEquals(0, model?.active)
        }

        @Test
        @DisplayName("deleteById - 删除不存在的模型")
        fun `deleteById should handle non-existent model`() {
            // When & Then - 不应该抛出异常
            modelService.deleteById(999L)
        }
    }

    @Nested
    @DisplayName("完整业务流程测试")
    inner class BusinessFlowTests {

        @Test
        @DisplayName("完整流程：创建 - 查询 - 更新 - 切换状态 - 删除")
        fun `complete flow create query update toggle delete`() {
            // 1. 创建模型
            val createRequest = ModelCreateRequest(
                name = "FlowTest Model",
                modelName = "flowtest-model",
                providerId = 1L,
                description = "流程测试模型",
                modelType = "chat",
                price = 0.0100,
                status = 1
            )
            val created = modelService.create(createRequest)
            assertNotNull(created)

            // 2. 查询模型
            val modelId = created.id ?: throw IllegalStateException("Created model ID should not be null")
            val model = modelService.getModelById(modelId)
            assertNotNull(model)

            // 3. 更新模型
            val updateRequest = ModelUpdateRequest(
                description = "更新后的流程测试模型",
                price = 0.0150
            )
            val updated = modelService.update(modelId, updateRequest)
            assertEquals("更新后的流程测试模型", updated.description)
            assertEquals(0.0150, updated.price)

            // 4. 切换状态
            val toggled = modelService.toggle(modelId)
            assertEquals(0, toggled.status)

            // 5. 删除模型
            modelService.deleteById(modelId)
            val deleted = modelMapper.selectById(modelId)
            assertEquals(0, deleted?.active)
        }
    }
}
