package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.ModelCreateRequest
import com.agnetix.harnax.admin.dto.ModelResponse
import com.agnetix.harnax.admin.dto.ModelUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.ModelService
import com.agnetix.harnax.entity.Model
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
 * ModelController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelControllerTest {

    @Mock
    private lateinit var modelService: ModelService

    @InjectMocks
    private lateinit var controller: ModelController

    private lateinit var testModel: Model
    private lateinit var testResponse: ModelResponse

    @BeforeEach
    fun setUp() {
        testModel = Model().apply {
            id = 1L
            tenantId = 1L
            name = "GPT-4"
            modelName = "gpt-4"
            providerId = 10L
            description = "OpenAI GPT-4"
            modelType = "chat"
            supportInternet = 0
            supportReasoning = 1
            supportTool = 1
            price = 30.0
            status = 1
            isPublic = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        testResponse = ModelResponse(
            id = 1L,
            name = "GPT-4",
            modelName = "gpt-4",
            providerId = 10L,
            modelType = "chat",
            status = 1,
        )
    }

    @Nested
    @DisplayName("GET /api/admin/models/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("pageModel - 返回分页结果")
        fun `pageModel should return paginated results`() {
            val page = Page(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testModel))
            `when`(modelService.page(null, null, null, null, null, null, null, 1, 10)).thenReturn(page)
            `when`(modelService.convertToResponse(testModel)).thenReturn(testResponse)

            val result = controller.pageModel(null, null, null, null, null, null, null, 1, 10)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals(1, result.data?.records?.size)
            assertEquals("GPT-4", result.data?.records?.get(0)?.name)
        }

        @Test
        @DisplayName("pageModel - pageNum/pageSize 为 null 时使用默认值 1/10")
        fun `pageModel should use default pagination when null`() {
            val page = Page<Model>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(modelService.page(null, null, null, null, null, null, null, 1, 10)).thenReturn(page)

            val result = controller.pageModel(null, null, null, null, null, null, null, null, null)

            assertTrue(result.isSuccess())
            verify(modelService).page(null, null, null, null, null, null, null, 1, 10)
        }

        @Test
        @DisplayName("pageModel - 透传筛选条件")
        fun `pageModel should pass filters correctly`() {
            val page = Page<Model>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(modelService.page("gpt", 10L, "chat", 1, "reasoning,tool", 0.0, 100.0, 2, 20)).thenReturn(page)

            val result = controller.pageModel("gpt", 10L, "chat", 1, "reasoning,tool", 0.0, 100.0, 2, 20)

            assertTrue(result.isSuccess())
            verify(modelService).page("gpt", 10L, "chat", 1, "reasoning,tool", 0.0, 100.0, 2, 20)
        }

        @Test
        @DisplayName("pageModel - service 抛异常时返回错误")
        fun `pageModel should return error on service exception`() {
            `when`(modelService.page(null, null, null, null, null, null, null, 1, 10))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.pageModel(null, null, null, null, null, null, null, 1, 10)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }

        @Test
        @DisplayName("pageModel - 异常无 message 时返回默认错误消息")
        fun `pageModel should return default error message when exception message is null`() {
            `when`(modelService.page(null, null, null, null, null, null, null, 1, 10))
                .thenThrow(RuntimeException())

            val result = controller.pageModel(null, null, null, null, null, null, null, 1, 10)

            assertFalse(result.isSuccess())
            assertEquals("Failed to get model list", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/models/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getModel - 模型存在时返回详情")
        fun `getModel should return model when found`() {
            `when`(modelService.getModel(1L)).thenReturn(testModel)
            `when`(modelService.convertToResponse(testModel)).thenReturn(testResponse)

            val result = controller.getModel(1L)

            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals("GPT-4", result.data?.name)
            assertEquals("gpt-4", result.data?.modelName)
        }

        @Test
        @DisplayName("getModel - 模型不存在时 data 为 null")
        fun `getModel should return null data when not found`() {
            `when`(modelService.getModel(999L)).thenReturn(null)

            val result = controller.getModel(999L)

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/models")
    inner class CreateEndpoint {

        private val request = ModelCreateRequest(
            name = "GPT-4",
            modelName = "gpt-4",
            providerId = 10L,
            modelType = "chat",
        )

        @Test
        @DisplayName("createModel - 创建成功")
        fun `createModel should return success`() {
            `when`(modelService.createModel(any())).thenReturn(true)

            val result = controller.createModel(request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("createModel - 创建失败时返回错误")
        fun `createModel should return error when service returns false`() {
            `when`(modelService.createModel(any())).thenReturn(false)

            val result = controller.createModel(request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to create model", result.message)
        }

        @Test
        @DisplayName("createModel - service 抛异常时异常向上传播")
        fun `createModel should propagate service exception`() {
            `when`(modelService.createModel(any())).thenThrow(BizException("Model name already exists"))

            assertThrows<BizException> {
                controller.createModel(request)
            }
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/models/update/{id}")
    inner class UpdateEndpoint {

        private val request = ModelUpdateRequest(name = "GPT-4 Turbo")

        @Test
        @DisplayName("updateModel - 更新成功")
        fun `updateModel should return success`() {
            `when`(modelService.updateModel(any(), any())).thenReturn(true)

            val result = controller.updateModel(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("updateModel - 更新失败时返回错误")
        fun `updateModel should return error when service returns false`() {
            `when`(modelService.updateModel(any(), any())).thenReturn(false)

            val result = controller.updateModel(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update model", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/models/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggleModel - 切换状态成功")
        fun `toggleModel should return success`() {
            `when`(modelService.toggleModel(1L, 1)).thenReturn(true)

            val result = controller.toggleModel(1L, 1)

            assertTrue(result.isSuccess())
            verify(modelService).toggleModel(1L, 1)
        }

        @Test
        @DisplayName("toggleModel - 切换失败时返回错误")
        fun `toggleModel should return error when service returns false`() {
            `when`(modelService.toggleModel(1L, 0)).thenReturn(false)

            val result = controller.toggleModel(1L, 0)

            assertFalse(result.isSuccess())
            assertEquals("Failed to toggle model status", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/models/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("deleteModel - 删除成功")
        fun `deleteModel should return success`() {
            `when`(modelService.deleteModel(1L)).thenReturn(true)

            val result = controller.deleteModel(1L)

            assertTrue(result.isSuccess())
            verify(modelService).deleteModel(1L)
        }

        @Test
        @DisplayName("deleteModel - service 抛异常时异常向上传播")
        fun `deleteModel should propagate service exception`() {
            `when`(modelService.deleteModel(999L)).thenThrow(BizException("Model not found"))

            assertThrows<BizException> {
                controller.deleteModel(999L)
            }
        }
    }
}
