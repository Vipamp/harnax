package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.ModelCreateRequest
import com.agnetix.harnax.admin.dto.ModelUpdateRequest
import com.agnetix.harnax.admin.entity.Model
import com.agnetix.harnax.admin.entity.ModelProvider
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.mapper.ModelMapper
import com.agnetix.harnax.admin.mapper.ModelProviderMapper
import com.agnetix.harnax.admin.util.JwtUtil
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.never
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

/**
 * ModelServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper layer dependencies
 *
 * @author agnetix
 * @since 2026-05-16
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelServiceImplTest {

    @Mock
    private lateinit var modelMapper: ModelMapper

    @Mock
    private lateinit var modelProviderMapper: ModelProviderMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @InjectMocks
    private lateinit var modelService: ModelServiceImpl

    private lateinit var testModel: Model
    private lateinit var testProvider: ModelProvider

    @BeforeEach
    fun setUp() {
        testProvider = ModelProvider().apply {
            id = 1L
            name = "OpenAI"
            status = 1
            active = 1
        }

        testModel = Model().apply {
            id = 1L
            name = "GPT-4"
            modelName = "gpt-4"
            providerId = 1L
            description = "Advanced language model"
            modelType = "chat"
            supportInternet = 1
            supportReasoning = 1
            supportTool = 1
            supportMcp = 0
            supportVision = 1
            price = 0.03
            isPublic = 1
            status = 1
            active = 1
            tenantId = 1L
            creator = "admin"
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        // Mock HttpServletRequest for UserContextUtil
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))

        // Mock JwtUtil
        `when`(jwtUtil.validateToken(anyString())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(anyString())).thenReturn("admin")
    }

    @Nested
    @DisplayName("Page Query Tests")
    inner class PageTests {

        @Test
        @DisplayName("page - Query with all filters")
        fun `page should return filtered results`() {
            // Given
            val models = listOf(testModel)
            `when`(
                modelMapper.selectModelList(
                    "GPT-4",
                    1L,
                    "chat",
                    1,
                    listOf("advanced"),
                    0.01,
                    0.05,
                    "admin",
                ),
            ).thenReturn(models)

            // When
            val result = modelService.page(
                "GPT-4",
                1L,
                "chat",
                1,
                "advanced",
                0.01,
                0.05,
                1,
                10,
            )

            // Then
            assertNotNull(result)
            verify(modelMapper).selectModelList(
                "GPT-4",
                1L,
                "chat",
                1,
                listOf("advanced"),
                0.01,
                0.05,
                "admin",
            )
        }

        @Test
        @DisplayName("page - Query with null filters")
        fun `page should return all results when filters are null`() {
            // Given
            val models = listOf(testModel)
            `when`(
                modelMapper.selectModelList(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "admin",
                ),
            ).thenReturn(models)

            // When
            val result = modelService.page(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                10,
            )

            // Then
            assertNotNull(result)
            verify(modelMapper).selectModelList(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "admin",
            )
        }
    }

    @Nested
    @DisplayName("Get Model Tests")
    inner class GetModelTests {

        @Test
        @DisplayName("getModel - Get model by ID successfully")
        fun `getModel should return model by id`() {
            // Given
            `when`(modelMapper.selectById(1L)).thenReturn(testModel)

            // When
            val result = modelService.getModel(1L)

            // Then
            assertNotNull(result)
            assertEquals("GPT-4", result?.name)
            assertEquals("gpt-4", result?.modelName)
            assertEquals(1L, result?.providerId)
            verify(modelMapper).selectById(1L)
        }

        @Test
        @DisplayName("getModel - Return null when model not found")
        fun `getModel should return null when model not found`() {
            // Given
            `when`(modelMapper.selectById(999L)).thenReturn(null)

            // When
            val result = modelService.getModel(999L)

            // Then
            assertNull(result)
            verify(modelMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("Create Model Tests")
    inner class CreateModelTests {

        @Test
        @DisplayName("createModel - Create model successfully")
        fun `createModel should create model successfully`() {
            // Given
            val request = ModelCreateRequest(
                name = "GPT-4o",
                modelName = "gpt-4o",
                providerId = 1L,
                description = "Multimodal model",
                modelType = "chat",
                supportInternet = 1,
                supportReasoning = 1,
                supportTool = 1,
                supportMcp = 0,
                supportVision = 1,
                price = 0.05,
                isPublic = 1,
            )

            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelMapper.countByProviderIdAndName(1L, "GPT-4o")).thenReturn(0)
            `when`(modelMapper.countByProviderIdAndModelName(1L, "gpt-4o")).thenReturn(0)
            `when`(modelMapper.insert(any())).thenReturn(1)

            // When
            val result = modelService.createModel(request)

            // Then
            assertTrue(result)
            verify(modelProviderMapper).selectById(1L)
            verify(modelMapper).countByProviderIdAndName(1L, "GPT-4o")
            verify(modelMapper).countByProviderIdAndModelName(1L, "gpt-4o")
            verify(modelMapper).insert(any())
        }

        @Test
        @DisplayName("createModel - Throw BizException when provider not found")
        fun `createModel should throw BizException when provider not found`() {
            // Given
            val request = ModelCreateRequest(
                name = "GPT-4o",
                modelName = "gpt-4o",
                providerId = 999L,
                modelType = "chat",
            )

            `when`(modelProviderMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                modelService.createModel(request)
            }
            assertEquals("Model provider not found", exception.message)
            verify(modelProviderMapper).selectById(999L)
            verify(modelMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createModel - Throw BizException when name exists")
        fun `createModel should throw BizException when name exists`() {
            // Given
            val request = ModelCreateRequest(
                name = "GPT-4",
                modelName = "gpt-4-new",
                providerId = 1L,
                modelType = "chat",
            )

            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelMapper.countByProviderIdAndName(1L, "GPT-4")).thenReturn(1)

            // When & Then
            val exception = assertThrows<BizException> {
                modelService.createModel(request)
            }
            assertEquals("Model name already exists under current provider", exception.message)
            verify(modelMapper, never()).countByProviderIdAndModelName(anyLong(), anyString())
            verify(modelMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createModel - Throw BizException when modelName exists")
        fun `createModel should throw BizException when modelName exists`() {
            // Given
            val request = ModelCreateRequest(
                name = "GPT-4o",
                modelName = "gpt-4",
                providerId = 1L,
                modelType = "chat",
            )

            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelMapper.countByProviderIdAndName(1L, "GPT-4o")).thenReturn(0)
            `when`(modelMapper.countByProviderIdAndModelName(1L, "gpt-4")).thenReturn(1)

            // When & Then
            val exception = assertThrows<BizException> {
                modelService.createModel(request)
            }
            assertEquals("Model identifier already exists under current provider", exception.message)
            verify(modelMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createModel - Create model with default values")
        fun `createModel should use default values for optional fields`() {
            // Given
            val request = ModelCreateRequest(
                name = "GPT-3.5",
                modelName = "gpt-3.5-turbo",
                providerId = 1L,
                modelType = "chat",
            )

            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelMapper.countByProviderIdAndName(1L, "GPT-3.5")).thenReturn(0)
            `when`(modelMapper.countByProviderIdAndModelName(1L, "gpt-3.5-turbo")).thenReturn(0)
            `when`(modelMapper.insert(any())).thenReturn(1)

            // When
            val result = modelService.createModel(request)

            // Then
            assertTrue(result)
            verify(modelMapper).insert(
                argThat { model ->
                    model != null &&
                        model.name == "GPT-3.5" &&
                        model.modelName == "gpt-3.5-turbo" &&
                        model.supportInternet == 0 &&
                        model.supportReasoning == 0 &&
                        model.supportTool == 0 &&
                        model.supportMcp == 0 &&
                        model.supportVision == 0 &&
                        model.price == 0.0 &&
                        model.isPublic == 1
                },
            )
        }
    }

    @Nested
    @DisplayName("Update Model Tests")
    inner class UpdateModelTests {

        @Test
        @DisplayName("updateModel - Update partial fields successfully")
        fun `updateModel should update partial fields successfully`() {
            // Given
            val request = ModelUpdateRequest(
                description = "Updated description",
                price = 0.04,
            )

            `when`(modelMapper.selectById(1L)).thenReturn(testModel)
            `when`(modelMapper.updateById(any())).thenReturn(1)

            // When
            val result = modelService.updateModel(1L, request)

            // Then
            assertTrue(result)
            verify(modelMapper).selectById(1L)
            verify(modelMapper).updateById(
                argThat { model ->
                    model != null &&
                        model.description == "Updated description" &&
                        model.price == 0.04 &&
                        model.name == "GPT-4" // Unchanged
                },
            )
        }

        @Test
        @DisplayName("updateModel - Throw BizException when model not found")
        fun `updateModel should throw BizException when model not found`() {
            // Given
            val request = ModelUpdateRequest(description = "Updated")

            `when`(modelMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                modelService.updateModel(999L, request)
            }
            assertEquals("Model not found", exception.message)
            verify(modelMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateModel - Throw BizException when provider not found")
        fun `updateModel should throw BizException when provider not found`() {
            // Given
            val request = ModelUpdateRequest(providerId = 999L)

            `when`(modelMapper.selectById(1L)).thenReturn(testModel)
            `when`(modelProviderMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                modelService.updateModel(1L, request)
            }
            assertEquals("Model provider not found", exception.message)
            verify(modelMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateModel - Throw BizException when name exists")
        fun `updateModel should throw BizException when name exists`() {
            // Given
            val request = ModelUpdateRequest(name = "GPT-4-Existing")

            `when`(modelMapper.selectById(1L)).thenReturn(testModel)
            `when`(modelMapper.countByProviderIdAndName(1L, "GPT-4-Existing")).thenReturn(1)

            // When & Then
            val exception = assertThrows<BizException> {
                modelService.updateModel(1L, request)
            }
            assertEquals("Model name already exists under current provider", exception.message)
            verify(modelMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateModel - Throw BizException when modelName exists")
        fun `updateModel should throw BizException when modelName exists`() {
            // Given
            val request = ModelUpdateRequest(modelName = "gpt-4-existing")

            `when`(modelMapper.selectById(1L)).thenReturn(testModel)
            `when`(modelMapper.countByProviderIdAndModelName(1L, "gpt-4-existing")).thenReturn(1)

            // When & Then
            val exception = assertThrows<BizException> {
                modelService.updateModel(1L, request)
            }
            assertEquals("Model identifier already exists under current provider", exception.message)
            verify(modelMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateModel - Update provider successfully")
        fun `updateModel should update provider successfully`() {
            // Given
            val newProvider = ModelProvider().apply {
                id = 2L
                name = "Anthropic"
                status = 1
            }

            val request = ModelUpdateRequest(providerId = 2L)

            `when`(modelMapper.selectById(1L)).thenReturn(testModel)
            `when`(modelProviderMapper.selectById(2L)).thenReturn(newProvider)
            `when`(modelMapper.updateById(any())).thenReturn(1)

            // When
            val result = modelService.updateModel(1L, request)

            // Then
            assertTrue(result)
            verify(modelMapper).updateById(
                argThat { model ->
                    model != null && model.providerId == 2L
                },
            )
        }
    }

    @Nested
    @DisplayName("Update Status Tests")
    inner class UpdateStatusTests {

        @Test
        @DisplayName("updateStatus - Enable model successfully")
        fun `updateStatus should enable model successfully`() {
            // Given
            `when`(modelMapper.selectById(1L)).thenReturn(testModel)
            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelMapper.updateStatus(1L, 1)).thenReturn(1)

            // When
            val result = modelService.updateStatus(1L, 1)

            // Then
            assertTrue(result)
            verify(modelMapper).selectById(1L)
            verify(modelProviderMapper).selectById(1L)
            verify(modelMapper).updateStatus(1L, 1)
        }

        @Test
        @DisplayName("updateStatus - Disable model successfully")
        fun `updateStatus should disable model successfully`() {
            // Given
            `when`(modelMapper.selectById(1L)).thenReturn(testModel)
            `when`(modelMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = modelService.updateStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(modelMapper).selectById(1L)
            verify(modelMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("updateStatus - Throw BizException when model not found")
        fun `updateStatus should throw BizException when model not found`() {
            // Given
            `when`(modelMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                modelService.updateStatus(999L, 1)
            }
            assertEquals("Model not found", exception.message)
        }

        @Test
        @DisplayName("updateStatus - Throw BizException when provider not found")
        fun `updateStatus should throw BizException when provider not found`() {
            // Given
            `when`(modelMapper.selectById(1L)).thenReturn(testModel)
            `when`(modelProviderMapper.selectById(1L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                modelService.updateStatus(1L, 1)
            }
            assertEquals("Model provider not found", exception.message)
        }

        @Test
        @DisplayName("updateStatus - Throw BizException when provider disabled")
        fun `updateStatus should throw BizException when provider disabled`() {
            // Given
            val disabledProvider = ModelProvider().apply {
                id = 1L
                name = "OpenAI"
                status = 0
            }

            `when`(modelMapper.selectById(1L)).thenReturn(testModel)
            `when`(modelProviderMapper.selectById(1L)).thenReturn(disabledProvider)

            // When & Then
            val exception = assertThrows<BizException> {
                modelService.updateStatus(1L, 1)
            }
            assertEquals("Provider is disabled, cannot enable model", exception.message)
        }
    }

    @Nested
    @DisplayName("Toggle Model Tests")
    inner class ToggleModelTests {

        @Test
        @DisplayName("toggleModel - Toggle model status successfully")
        fun `toggleModel should toggle model status successfully`() {
            // Given
            `when`(modelMapper.selectById(1L)).thenReturn(testModel)
            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = modelService.toggleModel(1L, 0)

            // Then
            assertTrue(result)
            verify(modelMapper).updateStatus(1L, 0)
        }
    }

    @Nested
    @DisplayName("Delete Model Tests")
    inner class DeleteModelTests {

        @Test
        @DisplayName("deleteModel - Delete model successfully")
        fun `deleteModel should delete model successfully`() {
            // Given
            `when`(modelMapper.selectById(1L)).thenReturn(testModel)
            `when`(modelMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = modelService.deleteModel(1L)

            // Then
            assertTrue(result)
            verify(modelMapper).selectById(1L)
            verify(modelMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteModel - Throw BizException when model not found")
        fun `deleteModel should throw BizException when model not found`() {
            // Given
            `when`(modelMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                modelService.deleteModel(999L)
            }
            assertEquals("Model not found", exception.message)
            verify(modelMapper, never()).deleteById(anyLong())
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Convert model to response with provider name")
        fun `convertToResponse should convert model to response with provider name`() {
            // Given
            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)

            // When
            val result = modelService.convertToResponse(testModel)

            // Then
            assertNotNull(result)
            assertEquals("GPT-4", result.name)
            assertEquals("gpt-4", result.modelName)
            assertEquals("OpenAI", result.providerName)
            verify(modelProviderMapper).selectById(1L)
        }

        @Test
        @DisplayName("convertToResponse - Convert model without provider")
        fun `convertToResponse should convert model when provider not found`() {
            // Given
            `when`(modelProviderMapper.selectById(999L)).thenReturn(null)

            val modelWithoutProvider = Model().apply {
                id = 2L
                name = "Unknown Model"
                modelName = "unknown"
                providerId = 999L
            }

            // When
            val result = modelService.convertToResponse(modelWithoutProvider)

            // Then
            assertNotNull(result)
            assertEquals("Unknown Model", result.name)
            assertNull(result.providerName)
            verify(modelProviderMapper).selectById(999L)
        }
    }
}
