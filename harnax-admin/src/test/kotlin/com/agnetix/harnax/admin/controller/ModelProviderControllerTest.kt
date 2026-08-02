package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.ModelProviderCreateRequest
import com.agnetix.harnax.admin.dto.ModelProviderResponse
import com.agnetix.harnax.admin.dto.ModelProviderUpdateRequest
import com.agnetix.harnax.admin.dto.ModelStatsInfo
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.ModelProviderService
import com.agnetix.harnax.entity.ModelProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * ModelProviderController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelProviderControllerTest {

    @Mock
    private lateinit var modelProviderService: ModelProviderService

    @InjectMocks
    private lateinit var controller: ModelProviderController

    private lateinit var testProvider: ModelProvider
    private lateinit var testResponse: ModelProviderResponse

    @BeforeEach
    fun setUp() {
        testProvider = ModelProvider().apply {
            id = 1L
            tenantId = 1L
            type = "openai"
            name = "OpenAI"
            description = "OpenAI provider"
            apiKey = "sk-xxxx"
            baseUrl = "https://api.openai.com/v1"
            status = 1
            isPublic = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        testResponse = ModelProviderResponse(
            id = 1L,
            type = "openai",
            name = "OpenAI",
            description = "OpenAI provider",
            apiKey = "sk-****",
            baseUrl = "https://api.openai.com/v1",
            status = 1,
            isPublic = 1,
            creator = "admin",
        )
    }

    @Nested
    @DisplayName("GET /api/admin/model-providers/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("pageModelProvider - 返回分页结果")
        fun `pageModelProvider should return paginated results`() {
            val page = Page(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testProvider))
            `when`(modelProviderService.page(null, null, null, null, 1, 10)).thenReturn(page)
            `when`(modelProviderService.convertToResponse(testProvider)).thenReturn(testResponse)

            val result = controller.pageModelProvider(null, null, null, null, 1, 10)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("OpenAI", result.data?.records?.get(0)?.name)
        }

        @Test
        @DisplayName("pageModelProvider - pageNum/pageSize 为 null 时使用默认值 1/10")
        fun `pageModelProvider should use default pagination when null`() {
            val page = Page<ModelProvider>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(modelProviderService.page(null, null, null, null, 1, 10)).thenReturn(page)

            val result = controller.pageModelProvider(null, null, null, null, null, null)

            assertTrue(result.isSuccess())
            verify(modelProviderService).page(null, null, null, null, 1, 10)
        }

        @Test
        @DisplayName("pageModelProvider - 透传筛选条件")
        fun `pageModelProvider should pass filters correctly`() {
            val page = Page<ModelProvider>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(modelProviderService.page("open", "openai", 1, 1, 2, 20)).thenReturn(page)

            val result = controller.pageModelProvider("open", "openai", 1, 1, 2, 20)

            assertTrue(result.isSuccess())
            verify(modelProviderService).page("open", "openai", 1, 1, 2, 20)
        }

        @Test
        @DisplayName("pageModelProvider - service 抛异常时返回错误")
        fun `pageModelProvider should return error on service exception`() {
            `when`(modelProviderService.page(null, null, null, null, 1, 10))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.pageModelProvider(null, null, null, null, 1, 10)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }

        @Test
        @DisplayName("pageModelProvider - 异常无 message 时返回默认错误消息")
        fun `pageModelProvider should return default error message when exception message is null`() {
            `when`(modelProviderService.page(null, null, null, null, 1, 10))
                .thenThrow(RuntimeException())

            val result = controller.pageModelProvider(null, null, null, null, 1, 10)

            assertFalse(result.isSuccess())
            assertEquals("Failed to get model provider list", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/model-providers/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getModelProvider - 存在时返回详情")
        fun `getModelProvider should return provider when found`() {
            `when`(modelProviderService.getModelProvider(1L)).thenReturn(testProvider)
            `when`(modelProviderService.convertToResponse(testProvider)).thenReturn(testResponse)

            val result = controller.getModelProvider(1L)

            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals("OpenAI", result.data?.name)
            assertEquals("openai", result.data?.type)
        }

        @Test
        @DisplayName("getModelProvider - 不存在时 data 为 null")
        fun `getModelProvider should return null data when not found`() {
            `when`(modelProviderService.getModelProvider(999L)).thenReturn(null)

            val result = controller.getModelProvider(999L)

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/model-providers")
    inner class CreateEndpoint {

        private val request = ModelProviderCreateRequest(
            type = "openai",
            name = "OpenAI",
            apiKey = "sk-xxxx",
            baseUrl = "https://api.openai.com/v1",
        )

        @Test
        @DisplayName("createModelProvider - 创建成功")
        fun `createModelProvider should return success`() {
            `when`(modelProviderService.createModelProvider(any())).thenReturn(true)

            val result = controller.createModelProvider(request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("createModelProvider - 创建失败时返回错误")
        fun `createModelProvider should return error when service returns false`() {
            `when`(modelProviderService.createModelProvider(any())).thenReturn(false)

            val result = controller.createModelProvider(request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to create model provider", result.message)
        }

        @Test
        @DisplayName("createModelProvider - service 抛异常时异常向上传播")
        fun `createModelProvider should propagate service exception`() {
            `when`(modelProviderService.createModelProvider(any()))
                .thenThrow(BizException("Provider name already exists"))

            assertThrows<BizException> {
                controller.createModelProvider(request)
            }
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/model-providers/update/{id}")
    inner class UpdateEndpoint {

        private val request = ModelProviderUpdateRequest(name = "OpenAI Updated")

        @Test
        @DisplayName("updateModelProvider - 更新成功")
        fun `updateModelProvider should return success`() {
            `when`(modelProviderService.updateModelProvider(any(), any())).thenReturn(true)

            val result = controller.updateModelProvider(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("updateModelProvider - 更新失败时返回错误")
        fun `updateModelProvider should return error when service returns false`() {
            `when`(modelProviderService.updateModelProvider(any(), any())).thenReturn(false)

            val result = controller.updateModelProvider(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update model provider", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/model-providers/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggleModelProvider - 切换成功")
        fun `toggleModelProvider should return success`() {
            `when`(modelProviderService.toggleModelProvider(1L, 1)).thenReturn(true)

            val result = controller.toggleModelProvider(1L, 1)

            assertTrue(result.isSuccess())
            verify(modelProviderService).toggleModelProvider(1L, 1)
        }

        @Test
        @DisplayName("toggleModelProvider - 切换失败时返回错误")
        fun `toggleModelProvider should return error when service returns false`() {
            `when`(modelProviderService.toggleModelProvider(1L, 0)).thenReturn(false)

            val result = controller.toggleModelProvider(1L, 0)

            assertFalse(result.isSuccess())
            assertEquals("Failed to toggle model provider status", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/model-providers/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("delete - 删除成功")
        fun `delete should return success`() {
            `when`(modelProviderService.deleteModelProvider(1L)).thenReturn(true)

            val result = controller.delete(1L)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("delete - 删除失败时返回错误")
        fun `delete should return error when service returns false`() {
            `when`(modelProviderService.deleteModelProvider(999L)).thenReturn(false)

            val result = controller.delete(999L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete model provider", result.message)
        }

        @Test
        @DisplayName("delete - service 抛异常时异常向上传播")
        fun `delete should propagate service exception`() {
            `when`(modelProviderService.deleteModelProvider(1L))
                .thenThrow(BizException("Provider has associated models"))

            assertThrows<BizException> {
                controller.delete(1L)
            }
        }
    }

    @Nested
    @DisplayName("POST /api/admin/model-providers/{id}/test")
    inner class ConnectivityTestEndpoint {

        @Test
        @DisplayName("connectivityTest - 连接成功返回 true")
        fun `connectivityTest should return true when connection ok`() {
            `when`(modelProviderService.connectivityTest(1L)).thenReturn(true)

            val result = controller.connectivityTest(1L)

            assertTrue(result.isSuccess())
            assertEquals(true, result.data)
        }

        @Test
        @DisplayName("connectivityTest - 连接失败返回 false")
        fun `connectivityTest should return false when connection fails`() {
            `when`(modelProviderService.connectivityTest(1L)).thenReturn(false)

            val result = controller.connectivityTest(1L)

            assertTrue(result.isSuccess())
            assertEquals(false, result.data)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/model-providers/{id}/stats")
    inner class StatsEndpoint {

        @Test
        @DisplayName("getModelStats - 返回模型统计信息")
        fun `getModelStats should return stats`() {
            val stats = ModelStatsInfo(totalModels = 5, enabledModels = 3, disabledModels = 2)
            `when`(modelProviderService.getModelStats(1L)).thenReturn(stats)

            val result = controller.getModelStats(1L)

            assertTrue(result.isSuccess())
            assertEquals(5, result.data?.totalModels)
            assertEquals(3, result.data?.enabledModels)
            assertEquals(2, result.data?.disabledModels)
        }

        @Test
        @DisplayName("getModelStats - service 抛异常时异常向上传播")
        fun `getModelStats should propagate service exception`() {
            `when`(modelProviderService.getModelStats(999L))
                .thenThrow(RuntimeException("DB error"))

            assertThrows<RuntimeException> {
                controller.getModelStats(999L)
            }
        }
    }
}
