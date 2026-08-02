package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.AgentToolResponse
import com.agnetix.harnax.admin.dto.AgentToolUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.AgentToolService
import com.agnetix.harnax.entity.AgentTool
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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
 * AgentToolController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentToolControllerTest {

    @Mock
    private lateinit var agentToolService: AgentToolService

    @InjectMocks
    private lateinit var controller: AgentToolController

    private lateinit var testTool: AgentTool
    private lateinit var testResponse: AgentToolResponse

    @BeforeEach
    fun setUp() {
        testTool = AgentTool().apply {
            id = 1L
            tenantId = 1L
            name = "send_email"
            displayName = "Send Email"
            description = "Send an email"
            type = "BUILTIN"
            beanName = "emailTool"
            methodName = "sendEmail"
            status = 1
            isPublic = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        testResponse = AgentToolResponse(
            id = 1L,
            name = "send_email",
            displayName = "Send Email",
            description = "Send an email",
            type = "BUILTIN",
            status = 1,
        )
    }

    @Nested
    @DisplayName("GET /api/admin/tools/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("pageAgentTool - 返回分页结果")
        fun `pageAgentTool should return paginated results`() {
            val page = Page(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testTool))
            `when`(agentToolService.page(null, null, null, 1, 10)).thenReturn(page)
            `when`(agentToolService.convertToResponse(testTool)).thenReturn(testResponse)

            val result = controller.pageAgentTool(1, 10, null, null, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("send_email", result.data?.records?.get(0)?.name)
        }

        @Test
        @DisplayName("pageAgentTool - pageNum/pageSize 为 null 时使用默认值 1/10")
        fun `pageAgentTool should use default pagination when null`() {
            val page = Page<AgentTool>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(agentToolService.page(null, null, null, 1, 10)).thenReturn(page)

            val result = controller.pageAgentTool(null, null, null, null, null)

            assertTrue(result.isSuccess())
            verify(agentToolService).page(null, null, null, 1, 10)
        }

        @Test
        @DisplayName("pageAgentTool - 透传筛选条件")
        fun `pageAgentTool should pass filters correctly`() {
            val page = Page<AgentTool>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(agentToolService.page("email", 1, "HTTP", 2, 20)).thenReturn(page)

            val result = controller.pageAgentTool(2, 20, "email", 1, "HTTP")

            assertTrue(result.isSuccess())
            verify(agentToolService).page("email", 1, "HTTP", 2, 20)
        }

        @Test
        @DisplayName("pageAgentTool - service 抛异常时返回错误")
        fun `pageAgentTool should return error on service exception`() {
            `when`(agentToolService.page(null, null, null, 1, 10))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.pageAgentTool(1, 10, null, null, null)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/tools/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getAgentTool - 存在时返回详情")
        fun `getAgentTool should return tool when found`() {
            `when`(agentToolService.getAgentTool(1L)).thenReturn(testTool)
            `when`(agentToolService.convertToResponse(testTool)).thenReturn(testResponse)

            val result = controller.getAgentTool(1L)

            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals("send_email", result.data?.name)
            assertEquals("BUILTIN", result.data?.type)
        }

        @Test
        @DisplayName("getAgentTool - 不存在时 data 为 null")
        fun `getAgentTool should return null data when not found`() {
            `when`(agentToolService.getAgentTool(999L)).thenReturn(null)

            val result = controller.getAgentTool(999L)

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getAgentTool - service 抛异常时返回错误")
        fun `getAgentTool should return error on service exception`() {
            `when`(agentToolService.getAgentTool(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.getAgentTool(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/tools/update/{id}")
    inner class UpdateEndpoint {

        private val request = AgentToolUpdateRequest(description = "Updated description")

        @Test
        @DisplayName("updateAgentTool - 更新成功")
        fun `updateAgentTool should return success`() {
            `when`(agentToolService.updateAgentTool(any(), any())).thenReturn(true)

            val result = controller.updateAgentTool(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("updateAgentTool - 更新失败时返回错误")
        fun `updateAgentTool should return error when service returns false`() {
            `when`(agentToolService.updateAgentTool(any(), any())).thenReturn(false)

            val result = controller.updateAgentTool(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update tool", result.message)
        }

        @Test
        @DisplayName("updateAgentTool - service 抛异常时返回错误")
        fun `updateAgentTool should return error on service exception`() {
            `when`(agentToolService.updateAgentTool(any(), any()))
                .thenThrow(BizException("Tool not found"))

            val result = controller.updateAgentTool(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Tool not found", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/tools/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggleAgentTool - 切换成功")
        fun `toggleAgentTool should return success`() {
            `when`(agentToolService.toggleAgentToolStatus(1L, 1)).thenReturn(true)

            val result = controller.toggleAgentTool(1L, 1)

            assertTrue(result.isSuccess())
            verify(agentToolService).toggleAgentToolStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleAgentTool - 切换失败时返回错误")
        fun `toggleAgentTool should return error when service returns false`() {
            `when`(agentToolService.toggleAgentToolStatus(1L, 0)).thenReturn(false)

            val result = controller.toggleAgentTool(1L, 0)

            assertFalse(result.isSuccess())
            assertEquals("Failed to toggle status", result.message)
        }

        @Test
        @DisplayName("toggleAgentTool - service 抛异常时返回错误")
        fun `toggleAgentTool should return error on service exception`() {
            `when`(agentToolService.toggleAgentToolStatus(999L, 1))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.toggleAgentTool(999L, 1)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/tools/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("deleteAgentTool - 删除成功")
        fun `deleteAgentTool should return success`() {
            `when`(agentToolService.deleteAgentTool(1L)).thenReturn(true)

            val result = controller.deleteAgentTool(1L)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("deleteAgentTool - 删除失败时返回错误")
        fun `deleteAgentTool should return error when service returns false`() {
            `when`(agentToolService.deleteAgentTool(999L)).thenReturn(false)

            val result = controller.deleteAgentTool(999L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete tool", result.message)
        }

        @Test
        @DisplayName("deleteAgentTool - service 抛异常时返回错误")
        fun `deleteAgentTool should return error on service exception`() {
            `when`(agentToolService.deleteAgentTool(1L))
                .thenThrow(BizException("Tool is in use"))

            val result = controller.deleteAgentTool(1L)

            assertFalse(result.isSuccess())
            assertEquals("Tool is in use", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/tools/available")
    inner class AvailableEndpoint {

        @Test
        @DisplayName("getAvailableTools - 返回可用工具列表")
        fun `getAvailableTools should return tool list`() {
            `when`(agentToolService.getAvailableToolsByType(null)).thenReturn(listOf(testTool))
            `when`(agentToolService.convertToResponse(testTool)).thenReturn(testResponse)

            val result = controller.getAvailableTools(null)

            assertTrue(result.isSuccess())
            assertEquals(1, result.data?.size)
            assertEquals("send_email", result.data?.get(0)?.name)
        }

        @Test
        @DisplayName("getAvailableTools - 按类型筛选")
        fun `getAvailableTools should filter by type`() {
            `when`(agentToolService.getAvailableToolsByType("BUILTIN")).thenReturn(listOf(testTool))
            `when`(agentToolService.convertToResponse(testTool)).thenReturn(testResponse)

            val result = controller.getAvailableTools("BUILTIN")

            assertTrue(result.isSuccess())
            verify(agentToolService).getAvailableToolsByType("BUILTIN")
        }

        @Test
        @DisplayName("getAvailableTools - 空列表")
        fun `getAvailableTools should return empty list when no tools`() {
            `when`(agentToolService.getAvailableToolsByType(null)).thenReturn(emptyList())

            val result = controller.getAvailableTools(null)

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("getAvailableTools - service 抛异常时返回错误")
        fun `getAvailableTools should return error on service exception`() {
            `when`(agentToolService.getAvailableToolsByType(null))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.getAvailableTools(null)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/tools/builtin")
    inner class BuiltinEndpoint {

        @Test
        @DisplayName("getBuiltinTools - 返回内置工具列表")
        fun `getBuiltinTools should return builtin tool list`() {
            `when`(agentToolService.getBuiltinTools()).thenReturn(listOf(testTool))
            `when`(agentToolService.convertToResponse(testTool)).thenReturn(testResponse)

            val result = controller.getBuiltinTools()

            assertTrue(result.isSuccess())
            assertEquals(1, result.data?.size)
            assertEquals("BUILTIN", result.data?.get(0)?.type)
        }

        @Test
        @DisplayName("getBuiltinTools - 空列表")
        fun `getBuiltinTools should return empty list when no builtin tools`() {
            `when`(agentToolService.getBuiltinTools()).thenReturn(emptyList())

            val result = controller.getBuiltinTools()

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("getBuiltinTools - service 抛异常时返回错误")
        fun `getBuiltinTools should return error on service exception`() {
            `when`(agentToolService.getBuiltinTools()).thenThrow(RuntimeException("DB error"))

            val result = controller.getBuiltinTools()

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/tools/{id}/required-env-params")
    inner class RequiredEnvParamsEndpoint {

        @Test
        @DisplayName("getRequiredEnvParamKeys - 返回必需环境参数列表")
        fun `getRequiredEnvParamKeys should return keys`() {
            `when`(agentToolService.getRequiredEnvParamKeys(1L)).thenReturn(listOf("SMTP_HOST", "SMTP_PASSWORD"))

            val result = controller.getRequiredEnvParamKeys(1L)

            assertTrue(result.isSuccess())
            assertEquals(listOf("SMTP_HOST", "SMTP_PASSWORD"), result.data)
        }

        @Test
        @DisplayName("getRequiredEnvParamKeys - 无必需参数时返回空列表")
        fun `getRequiredEnvParamKeys should return empty list when none required`() {
            `when`(agentToolService.getRequiredEnvParamKeys(1L)).thenReturn(emptyList())

            val result = controller.getRequiredEnvParamKeys(1L)

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("getRequiredEnvParamKeys - service 抛异常时返回错误")
        fun `getRequiredEnvParamKeys should return error on service exception`() {
            `when`(agentToolService.getRequiredEnvParamKeys(999L))
                .thenThrow(BizException("Tool not found"))

            val result = controller.getRequiredEnvParamKeys(999L)

            assertFalse(result.isSuccess())
            assertEquals("Tool not found", result.message)
        }
    }
}
