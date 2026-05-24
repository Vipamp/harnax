package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.ModelProviderCreateRequest
import com.agnetix.harnax.admin.dto.ModelProviderUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.ModelProvider
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.ModelProviderMapper
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
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * ModelProviderServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper layer dependencies
 *
 * @author agnetix
 * @since 2026-05-16
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelProviderServiceImplTest {

    @Mock
    private lateinit var modelProviderMapper: ModelProviderMapper

    @Mock
    private lateinit var modelMapper: ModelMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @InjectMocks
    private lateinit var modelProviderService: ModelProviderServiceImpl

    private lateinit var testProvider: ModelProvider

    @BeforeEach
    fun setUp() {
        testProvider = ModelProvider().apply {
            id = 1L
            name = "Test Provider"
            type = "openai"
            description = "Test provider description"
            apiKey = "sk-test-key"
            baseUrl = "https://api.test.com"
            isPublic = 1
            status = 1
            active = 1
            creator = "admin"
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
    inner class PageQueryTests {

        @Test
        @DisplayName("page - Return paginated provider list")
        fun `page should return paginated provider list`() {
            // Given
            val providers = listOf(testProvider)
            `when`(modelProviderMapper.selectModelProviderList(null, null, null, null, "admin"))
                .thenReturn(providers)

            // When
            val result = modelProviderService.page(null, null, null, null, 1, 10)

            // Then
            assertNotNull(result)
            verify(modelProviderMapper).selectModelProviderList(null, null, null, null, "admin")
        }

        @Test
        @DisplayName("page - Filter by name and status")
        fun `page should filter by name and status`() {
            // Given
            `when`(modelProviderMapper.selectModelProviderList("Test", "openai", 1, null, "admin"))
                .thenReturn(listOf(testProvider))

            // When
            val result = modelProviderService.page("Test", "openai", 1, null, 1, 10)

            // Then
            assertNotNull(result)
            verify(modelProviderMapper).selectModelProviderList("Test", "openai", 1, null, "admin")
        }
    }

    @Nested
    @DisplayName("Get Provider Tests")
    inner class GetProviderTests {

        @Test
        @DisplayName("getModelProvider - Return provider by ID")
        fun `getModelProvider should return provider by id`() {
            // Given
            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)

            // When
            val result = modelProviderService.getModelProvider(1L)

            // Then
            assertNotNull(result)
            assertEquals(1L, result!!.id)
            assertEquals("Test Provider", result.name)
            verify(modelProviderMapper).selectById(1L)
        }

        @Test
        @DisplayName("getModelProvider - Return null when provider not found")
        fun `getModelProvider should return null when provider not found`() {
            // Given
            `when`(modelProviderMapper.selectById(999L)).thenReturn(null)

            // When
            val result = modelProviderService.getModelProvider(999L)

            // Then
            assertNull(result)
            verify(modelProviderMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("Create Provider Tests")
    inner class CreateProviderTests {

        @Test
        @DisplayName("createModelProvider - Create provider successfully")
        fun `createModelProvider should create provider successfully`() {
            // Given
            val request = ModelProviderCreateRequest(
                name = "New Provider",
                type = "openai",
                description = "New provider description",
                apiKey = "sk-new-key",
                baseUrl = "https://api.new.com",
                isPublic = 1,
            )

            `when`(modelProviderMapper.countByName("New Provider")).thenReturn(0)
            `when`(modelProviderMapper.insert(any())).thenReturn(1)

            // When
            val result = modelProviderService.createModelProvider(request)

            // Then
            assertTrue(result)
            verify(modelProviderMapper).countByName("New Provider")
            verify(modelProviderMapper).insert(any())
        }

        @Test
        @DisplayName("createModelProvider - Throw exception when name exists")
        fun `createModelProvider should throw exception when name exists`() {
            // Given
            val request = ModelProviderCreateRequest(
                name = "Existing Provider",
                type = "openai",
            )

            `when`(modelProviderMapper.countByName("Existing Provider")).thenReturn(1)

            // When & Then
            val exception = assertThrows<BizException> {
                modelProviderService.createModelProvider(request)
            }
            assertEquals("Provider name already exists", exception.message)
            verify(modelProviderMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createModelProvider - Create provider with nullable fields")
        fun `createModelProvider should create provider with nullable fields`() {
            // Given
            val request = ModelProviderCreateRequest(
                name = "Minimal Provider",
                type = "anthropic",
                apiKey = null,
                baseUrl = null,
            )

            `when`(modelProviderMapper.countByName("Minimal Provider")).thenReturn(0)
            `when`(modelProviderMapper.insert(any())).thenReturn(1)

            // When
            val result = modelProviderService.createModelProvider(request)

            // Then
            assertTrue(result)
            verify(modelProviderMapper).insert(
                org.mockito.kotlin.argThat { provider: ModelProvider ->
                    provider.apiKey == null && provider.baseUrl == null
                },
            )
        }
    }

    @Nested
    @DisplayName("Update Provider Tests")
    inner class UpdateProviderTests {

        @Test
        @DisplayName("updateModelProvider - Update provider successfully")
        fun `updateModelProvider should update provider successfully`() {
            // Given
            val request = ModelProviderUpdateRequest(
                name = "Updated Provider",
                description = "Updated description",
            )

            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelProviderMapper.countByName("Updated Provider")).thenReturn(0)
            `when`(modelProviderMapper.updateById(any())).thenReturn(1)

            // When
            val result = modelProviderService.updateModelProvider(1L, request)

            // Then
            assertTrue(result)
            verify(modelProviderMapper).updateById(any())
        }

        @Test
        @DisplayName("updateModelProvider - Throw exception when provider not found")
        fun `updateModelProvider should throw exception when provider not found`() {
            // Given
            val request = ModelProviderUpdateRequest(name = "Updated")
            `when`(modelProviderMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                modelProviderService.updateModelProvider(999L, request)
            }
            assertEquals("Provider not found", exception.message)
        }

        @Test
        @DisplayName("updateModelProvider - Throw exception when name exists")
        fun `updateModelProvider should throw exception when name exists`() {
            // Given
            val request = ModelProviderUpdateRequest(name = "Duplicate Name")
            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelProviderMapper.countByName("Duplicate Name")).thenReturn(1)

            // When & Then
            val exception = assertThrows<BizException> {
                modelProviderService.updateModelProvider(1L, request)
            }
            assertEquals("Provider name already exists", exception.message)
        }

        @Test
        @DisplayName("updateModelProvider - Update API key when not masked")
        fun `updateModelProvider should update API key when not masked`() {
            // Given
            val request = ModelProviderUpdateRequest(
                apiKey = "sk-new-unmasked-key",
            )

            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelProviderMapper.updateById(any())).thenReturn(1)

            // When
            val result = modelProviderService.updateModelProvider(1L, request)

            // Then
            assertTrue(result)
            verify(modelProviderMapper).updateById(
                org.mockito.kotlin.argThat { provider: ModelProvider ->
                    provider.apiKey == "sk-new-unmasked-key"
                },
            )
        }

        @Test
        @DisplayName("updateModelProvider - Skip API key update when masked")
        fun `updateModelProvider should skip API key update when masked`() {
            // Given
            val originalApiKey = "sk-original-key"
            testProvider.apiKey = originalApiKey

            val request = ModelProviderUpdateRequest(
                apiKey = "sk-****-key",
            )

            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelProviderMapper.updateById(any())).thenReturn(1)

            // When
            val result = modelProviderService.updateModelProvider(1L, request)

            // Then
            assertTrue(result)
            verify(modelProviderMapper).updateById(
                org.mockito.kotlin.argThat { provider: ModelProvider ->
                    provider.apiKey == originalApiKey
                },
            )
        }

        @Test
        @DisplayName("updateModelProvider - Return false when update fails")
        fun `updateModelProvider should return false when update fails`() {
            // Given
            val request = ModelProviderUpdateRequest(description = "Updated")
            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelProviderMapper.updateById(any())).thenReturn(0)

            // When
            val result = modelProviderService.updateModelProvider(1L, request)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("Status Management Tests")
    inner class StatusManagementTests {

        @Test
        @DisplayName("updateStatus - Disable provider successfully")
        fun `updateStatus should disable provider successfully`() {
            // Given
            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelMapper.countActiveModelsByProviderId(1L)).thenReturn(0)
            `when`(modelProviderMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = modelProviderService.updateStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(modelProviderMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("updateStatus - Throw exception when has enabled models")
        fun `updateStatus should throw exception when has enabled models`() {
            // Given
            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelMapper.countActiveModelsByProviderId(1L)).thenReturn(3)

            // When & Then
            val exception = assertThrows<BizException> {
                modelProviderService.updateStatus(1L, 0)
            }
            assertEquals("Cannot disable: there are enabled models under this provider", exception.message)
            verify(modelProviderMapper, never()).updateStatus(anyLong(), anyInt())
        }

        @Test
        @DisplayName("updateStatus - Throw exception when provider not found")
        fun `updateStatus should throw exception when provider not found`() {
            // Given
            `when`(modelProviderMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                modelProviderService.updateStatus(999L, 0)
            }
            assertEquals("Model provider not found", exception.message)
        }

        @Test
        @DisplayName("toggleModelProvider - Toggle provider status")
        fun `toggleModelProvider should toggle provider status`() {
            // Given
            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)
            `when`(modelMapper.countActiveModelsByProviderId(1L)).thenReturn(0)
            `when`(modelProviderMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = modelProviderService.toggleModelProvider(1L, 0)

            // Then
            assertTrue(result)
        }
    }

    @Nested
    @DisplayName("Delete Provider Tests")
    inner class DeleteProviderTests {

        @Test
        @DisplayName("deleteModelProvider - Delete provider successfully")
        fun `deleteModelProvider should delete provider successfully`() {
            // Given
            `when`(modelMapper.countActiveModelsByProviderId(1L)).thenReturn(0)
            `when`(modelProviderMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = modelProviderService.deleteModelProvider(1L)

            // Then
            assertTrue(result)
            verify(modelProviderMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteModelProvider - Throw exception when has enabled models")
        fun `deleteModelProvider should throw exception when has enabled models`() {
            // Given
            `when`(modelMapper.countActiveModelsByProviderId(1L)).thenReturn(2)

            // When & Then
            val exception = assertThrows<BizException> {
                modelProviderService.deleteModelProvider(1L)
            }
            assertEquals("Cannot delete: there are enabled models under this provider", exception.message)
            verify(modelProviderMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteModelProvider - Return false when delete fails")
        fun `deleteModelProvider should return false when delete fails`() {
            // Given
            `when`(modelMapper.countActiveModelsByProviderId(1L)).thenReturn(0)
            `when`(modelProviderMapper.deleteById(1L)).thenReturn(0)

            // When
            val result = modelProviderService.deleteModelProvider(1L)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("Model Stats Tests")
    inner class ModelStatsTests {

        @Test
        @DisplayName("getModelStats - Return model statistics")
        fun `getModelStats should return model statistics`() {
            // Given
            `when`(modelMapper.countModelsByProviderId(1L)).thenReturn(10)
            `when`(modelMapper.countActiveModelsByProviderId(1L)).thenReturn(7)
            `when`(modelMapper.countDisabledModelsByProviderId(1L)).thenReturn(3)

            // When
            val result = modelProviderService.getModelStats(1L)

            // Then
            assertNotNull(result)
            assertEquals(10, result.totalModels)
            assertEquals(7, result.enabledModels)
            assertEquals(3, result.disabledModels)
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Convert entity to response")
        fun `convertToResponse should convert entity to response`() {
            // When
            val result = modelProviderService.convertToResponse(testProvider)

            // Then
            assertNotNull(result)
            assertEquals(1L, result.id)
            assertEquals("Test Provider", result.name)
            assertEquals("openai", result.type)
        }
    }

    @Nested
    @DisplayName("Connectivity Test")
    inner class ConnectivityTestTests {

        @Test
        @DisplayName("connectivityTest - Return true when provider exists")
        fun `connectivityTest should return true when provider exists`() {
            // Given
            `when`(modelProviderMapper.selectById(1L)).thenReturn(testProvider)

            // When
            val result = modelProviderService.connectivityTest(1L)

            // Then
            assertTrue(result)
        }

        @Test
        @DisplayName("connectivityTest - Throw exception when provider not found")
        fun `connectivityTest should throw exception when provider not found`() {
            // Given
            `when`(modelProviderMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                modelProviderService.connectivityTest(999L)
            }
            assertEquals("Model provider not found", exception.message)
        }
    }
}
