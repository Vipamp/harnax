package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.AgentToolResponse
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.AgentToolService
import com.agnetix.harnax.entity.AgentTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
            beanName = "emailTool"
            methodName = "sendEmail"
            status = 1
            creator = "SYSTEM"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        testResponse = AgentToolResponse(
            id = 1L,
            name = "send_email",
            displayName = "Send Email",
            description = "Send an email",
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
            `when`(agentToolService.page(null, null, 1, 10)).thenReturn(page)
            `when`(agentToolService.convertToResponse(testTool)).thenReturn(testResponse)

            val result = controller.pageAgentTool(1, 10, null, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("send_email", result.data?.records?.get(0)?.name)
        }

        @Test
        @DisplayName("pageAgentTool - pageNum/pageSize 为 null 时使用默认值 1/10")
        fun `pageAgentTool should use default pagination when null`() {
            val page = Page<AgentTool>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(agentToolService.page(null, null, 1, 10)).thenReturn(page)

            val result = controller.pageAgentTool(null, null, null, null)

            assertTrue(result.isSuccess())
            verify(agentToolService).page(null, null, 1, 10)
        }

        @Test
        @DisplayName("pageAgentTool - 透传筛选条件")
        fun `pageAgentTool should pass filters correctly`() {
            val page = Page<AgentTool>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(agentToolService.page("email", 1, 2, 20)).thenReturn(page)

            val result = controller.pageAgentTool(2, 20, "email", 1)

            assertTrue(result.isSuccess())
            verify(agentToolService).page("email", 1, 2, 20)
        }

        @Test
        @DisplayName("pageAgentTool - service 抛异常时返回错误")
        fun `pageAgentTool should return error on service exception`() {
            `when`(agentToolService.page(null, null, 1, 10))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.pageAgentTool(1, 10, null, null)

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
    @DisplayName("GET /api/admin/tools/available")
    inner class AvailableEndpoint {

        @Test
        @DisplayName("getAvailableTools - 返回可用工具列表")
        fun `getAvailableTools should return tool list`() {
            `when`(agentToolService.getAvailableTools()).thenReturn(listOf(testTool))
            `when`(agentToolService.convertToResponse(testTool)).thenReturn(testResponse)

            val result = controller.getAvailableTools()

            assertTrue(result.isSuccess())
            assertEquals(1, result.data?.size)
            assertEquals("send_email", result.data?.get(0)?.name)
        }

        @Test
        @DisplayName("getAvailableTools - 空列表")
        fun `getAvailableTools should return empty list when no tools`() {
            `when`(agentToolService.getAvailableTools()).thenReturn(emptyList())

            val result = controller.getAvailableTools()

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("getAvailableTools - service 抛异常时返回错误")
        fun `getAvailableTools should return error on service exception`() {
            `when`(agentToolService.getAvailableTools())
                .thenThrow(RuntimeException("DB error"))

            val result = controller.getAvailableTools()

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/tools/builtin")
    inner class BuiltinEndpoint {

        @Test
        @DisplayName("getBuiltinTools - 返回工具列表")
        fun `getBuiltinTools should return tool list`() {
            `when`(agentToolService.getBuiltinTools()).thenReturn(listOf(testTool))
            `when`(agentToolService.convertToResponse(testTool)).thenReturn(testResponse)

            val result = controller.getBuiltinTools()

            assertTrue(result.isSuccess())
            assertEquals(1, result.data?.size)
            assertEquals("send_email", result.data?.get(0)?.name)
        }

        @Test
        @DisplayName("getBuiltinTools - 空列表")
        fun `getBuiltinTools should return empty list when no tools`() {
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
