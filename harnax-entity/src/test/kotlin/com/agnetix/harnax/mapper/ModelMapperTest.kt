package com.agnetix.harnax.mapper

import com.agnetix.harnax.entity.Model
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
 * ModelMapper Integration Tests
 *
 * @author agnetix
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
    private lateinit var modelMapper: ModelMapper

    @Nested
    @DisplayName("Basic CRUD Tests")
    inner class BasicCrudTests {

        @Test
        @DisplayName("selectById - Query model by ID")
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
        @DisplayName("selectById - Return null when model not exists")
        fun `selectById should return null when model not exists`() {
            // When
            val model = modelMapper.selectById(999L)

            // Then
            assertNull(model)
        }

        @Test
        @DisplayName("selectById - Do not return deleted model")
        fun `selectById should not return deleted model`() {
            // When
            val model = modelMapper.selectById(5L)

            // Then
            assertNull(model)
        }

        @Test
        @DisplayName("insert - Insert new model")
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
        @DisplayName("updateById - Update model information")
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
        @DisplayName("deleteById - Logically delete model")
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
    @DisplayName("Status Management Tests")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - Update model status")
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
    @DisplayName("Custom Query Tests")
    inner class CustomQueryTests {

        @Test
        @DisplayName("selectModelList - Query all models")
        fun `selectModelList should return all models`() {
            // When
            val models = modelMapper.selectModelList(null, null, null, null, null, null, null, "admin")

            // Then
            assertTrue(models.isNotEmpty())
            assertTrue(models.size >= 4)
        }

        @Test
        @DisplayName("selectModelList - Filter by provider ID")
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
        @DisplayName("selectModelList - Filter by model type")
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
        @DisplayName("countByProviderIdAndName - Count models by provider and name")
        fun `countByProviderIdAndName should count models by provider and name`() {
            // When
            val count = modelMapper.countByProviderIdAndName(1L, "GPT-4")

            // Then
            assertEquals(1, count)
        }

        @Test
        @DisplayName("countByProviderIdAndModelName - Count models by provider and model name")
        fun `countByProviderIdAndModelName should count models by provider and model name`() {
            // When
            val count = modelMapper.countByProviderIdAndModelName(1L, "gpt-4")

            // Then
            assertEquals(1, count)
        }

        @Test
        @DisplayName("countActiveModelsByProviderId - Count active models by provider")
        fun `countActiveModelsByProviderId should count active models by provider`() {
            // When
            val count = modelMapper.countActiveModelsByProviderId(1L)

            // Then
            assertTrue(count >= 2) // At least 2 active models
        }
    }
}
