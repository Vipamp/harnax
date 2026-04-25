package com.vipamp.vipclaw.admin.mapper

import com.vipamp.vipclaw.admin.entity.Model
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
 * ModelMapper 集成测试
 *
 * @author vipamp
 * @since 2026-04-25
 */
@Testcontainers
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ModelMapperTest {

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
    private lateinit var modelMapper: ModelMapper

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - 根据 ID 查询模型")
        fun `selectById should return model by id`() {
            // When
            val model = modelMapper.selectById(1L)

            // Then
            assertNotNull(model)
            assertEquals(1L, model.id)
            assertEquals("GPT-4", model.name)
            assertEquals("gpt-4", model.modelName)
            assertEquals(1L, model.providerId)
            assertEquals("chat", model.modelType)
            assertEquals(1, model.status)
            assertEquals(1, model.active)
        }

        @Test
        @DisplayName("selectById - 查询不存在的模型返回 null")
        fun `selectById should return null when model not exists`() {
            // When
            val model = modelMapper.selectById(999L)

            // Then
            assertNull(model)
        }

        @Test
        @DisplayName("selectById - 不返回已删除的模型")
        fun `selectById should not return deleted model`() {
            // When
            val model = modelMapper.selectById(5L)

            // Then
            assertNull(model)
        }

        @Test
        @DisplayName("insert - 插入新模型")
        fun `insert should create new model`() {
            // Given
            val now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val newModel = Model().apply {
                name = "New Model"
                modelName = "new-model"
                providerId = 1L
                description = "新模型"
                modelType = "chat"
                supportInternet = 0
                supportReasoning = 0
                supportTool = 1
                supportMcp = 0
                supportVision = 0
                price = 0.0100
                status = 1
                isPublic = 1
                creator = "admin"
                active = 1
                createTime = now
                updateTime = now
            }

            // When
            val result = modelMapper.insert(newModel)

            // Then
            assertEquals(1, result)
            assertTrue(newModel.id > 0)

            val insertedModel = modelMapper.selectById(newModel.id)
            assertNotNull(insertedModel)
            assertEquals("New Model", insertedModel.name)
        }

        @Test
        @DisplayName("updateById - 更新模型信息")
        fun `updateById should update model info`() {
            // Given
            val modelId = 1L
            val model = modelMapper.selectById(modelId)
            assertNotNull(model)

            // When
            model.name = "Updated Model"
            model.description = "更新后的描述"
            model.updateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
            val result = modelMapper.updateById(model)

            // Then
            assertEquals(1, result)
            val updatedModel = modelMapper.selectById(modelId)
            assertNotNull(updatedModel)
            assertEquals("Updated Model", updatedModel.name)
        }

        @Test
        @DisplayName("deleteById - 逻辑删除模型")
        fun `deleteById should logically delete model`() {
            // Given
            val modelId = 2L
            val modelBefore = modelMapper.selectById(modelId)
            assertNotNull(modelBefore)

            // When
            val result = modelMapper.deleteById(modelId)

            // Then
            assertEquals(1, result)
            val deletedModel = modelMapper.selectById(modelId)
            assertNull(deletedModel)
        }
    }

    @Nested
    @DisplayName("状态管理测试")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - 更新模型状态")
        fun `updateStatus should update model status`() {
            // Given
            val modelId = 1L
            val newStatus = 0

            // When
            val result = modelMapper.updateStatus(modelId, newStatus)
            val updatedModel = modelMapper.selectById(modelId)

            // Then
            assertEquals(1, result)
            assertNotNull(updatedModel)
            assertEquals(newStatus, updatedModel.status)
        }
    }

    @Nested
    @DisplayName("自定义查询测试")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectModelList - 查询所有模型列表")
        fun `selectModelList should return all models`() {
            // When
            val models = modelMapper.selectModelList(null, null, null, null, null, null, null, "admin")

            // Then
            assertTrue(models.isNotEmpty())
            assertTrue(models.size >= 4)
        }

        @Test
        @DisplayName("selectModelList - 按服务商 ID 查询")
        fun `selectModelList should filter by provider id`() {
            // When
            val models = modelMapper.selectModelList(null, 1L, null, null, null, null, null, "admin")

            // Then
            assertTrue(models.isNotEmpty())
            models.forEach {
                assertEquals(1L, it.providerId)
            }
        }

        @Test
        @DisplayName("selectModelList - 按模型类型查询")
        fun `selectModelList should filter by model type`() {
            // When
            val models = modelMapper.selectModelList(null, null, "chat", null, null, null, null, "admin")

            // Then
            assertTrue(models.isNotEmpty())
            models.forEach {
                assertEquals("chat", it.modelType)
            }
        }

        @Test
        @DisplayName("countByProviderIdAndName - 统计服务商下指定名称的模型数量")
        fun `countByProviderIdAndName should count models by provider and name`() {
            // When
            val count = modelMapper.countByProviderIdAndName(1L, "GPT-4")

            // Then
            assertEquals(1, count)
        }

        @Test
        @DisplayName("countByProviderIdAndModelName - 统计服务商下指定模型名称的数量")
        fun `countByProviderIdAndModelName should count models by provider and model name`() {
            // When
            val count = modelMapper.countByProviderIdAndModelName(1L, "gpt-4")

            // Then
            assertEquals(1, count)
        }

        @Test
        @DisplayName("countActiveModelsByProviderId - 统计服务商下启用的模型数量")
        fun `countActiveModelsByProviderId should count active models by provider`() {
            // When
            val count = modelMapper.countActiveModelsByProviderId(1L)

            // Then
            assertTrue(count >= 2) // 至少有 2 个启用的模型
        }
    }
}
