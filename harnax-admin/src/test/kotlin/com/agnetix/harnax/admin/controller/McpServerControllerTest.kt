package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.McpServerCreateRequest
import com.agnetix.harnax.admin.dto.McpServerResponse
import com.agnetix.harnax.admin.dto.McpServerUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.entity.McpServer
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * McpServerController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpServerControllerTest {

    @Mock
    private lateinit var mcpServerService: McpServerService

    @InjectMocks
    private lateinit var controller: McpServerController

    private lateinit var testMcpServer: McpServer
    private lateinit var testResponse: McpServerResponse

    @BeforeEach
    fun setUp() {
        testMcpServer = McpServer().apply {
            id = 1L
            tenantId = 1L
            name = "test-mcp"
            description = "Test MCP server"
            type = "streamablehttp"
            url = "http://localhost:3000/mcp"
            status = 1
            isPublic = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        testResponse = McpServerResponse(
            id = 1L,
            name = "test-mcp",
            description = "Test MCP server",
            type = "streamablehttp",
            url = "http://localhost:3000/mcp",
            status = 1,
        )
    }

    @Nested
    @DisplayName("GET /api/admin/mcp/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("pageMcpServer - 返回分页结果")
        fun `pageMcpServer should return paginated results`() {
            val page = Page(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testMcpServer))
            `when`(mcpServerService.page(null, null, null, 1, 10)).thenReturn(page)
            `when`(mcpServerService.convertToResponse(testMcpServer)).thenReturn(testResponse)

            val result = controller.pageMcpServer(1, 10, null, null, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("test-mcp", result.data?.records?.get(0)?.name)
        }

        @Test
        @DisplayName("pageMcpServer - pageNum/pageSize 为 null 时使用默认值 1/10")
        fun `pageMcpServer should use default pagination when null`() {
            val page = Page<McpServer>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(mcpServerService.page(null, null, null, 1, 10)).thenReturn(page)

            val result = controller.pageMcpServer(null, null, null, null, null)

            assertTrue(result.isSuccess())
            verify(mcpServerService).page(null, null, null, 1, 10)
        }

        @Test
        @DisplayName("pageMcpServer - 透传筛选条件")
        fun `pageMcpServer should pass filters correctly`() {
            val page = Page<McpServer>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(mcpServerService.page("test", 1, "sse", 2, 20)).thenReturn(page)

            val result = controller.pageMcpServer(2, 20, "test", 1, "sse")

            assertTrue(result.isSuccess())
            verify(mcpServerService).page("test", 1, "sse", 2, 20)
        }

        @Test
        @DisplayName("pageMcpServer - service 抛异常时返回错误")
        fun `pageMcpServer should return error on service exception`() {
            `when`(mcpServerService.page(null, null, null, 1, 10))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.pageMcpServer(1, 10, null, null, null)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/mcp/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getMcpServer - 存在时返回详情")
        fun `getMcpServer should return server when found`() {
            `when`(mcpServerService.getMcpServer(1L)).thenReturn(testMcpServer)
            `when`(mcpServerService.convertToResponse(testMcpServer)).thenReturn(testResponse)

            val result = controller.getMcpServer(1L)

            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals("test-mcp", result.data?.name)
        }

        @Test
        @DisplayName("getMcpServer - 不存在时 data 为 null")
        fun `getMcpServer should return null data when not found`() {
            `when`(mcpServerService.getMcpServer(999L)).thenReturn(null)

            val result = controller.getMcpServer(999L)

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getMcpServer - service 抛异常时返回错误")
        fun `getMcpServer should return error on service exception`() {
            `when`(mcpServerService.getMcpServer(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.getMcpServer(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/mcp")
    inner class CreateEndpoint {

        @Test
        @DisplayName("createMcpServer - 创建成功")
        fun `createMcpServer should return success`() {
            val request = McpServerCreateRequest(
                name = "new-mcp",
                type = "streamablehttp",
                url = "http://localhost:3000/mcp",
            )
            `when`(mcpServerService.createMcpServer(any())).thenReturn(true)

            val result = controller.createMcpServer(request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("createMcpServer - stdio 类型不支持,直接返回错误")
        fun `createMcpServer should reject stdio type`() {
            val request = McpServerCreateRequest(
                name = "stdio-mcp",
                type = "stdio",
                command = "npx -y some-server",
            )

            val result = controller.createMcpServer(request)

            assertFalse(result.isSuccess())
            assertEquals("stdio mode is not supported in current edition", result.message)
            verify(mcpServerService, never()).createMcpServer(any())
        }

        @Test
        @DisplayName("createMcpServer - 创建失败时返回错误")
        fun `createMcpServer should return error when service returns false`() {
            val request = McpServerCreateRequest(
                name = "new-mcp",
                type = "sse",
                url = "http://localhost:3000/sse",
            )
            `when`(mcpServerService.createMcpServer(any())).thenReturn(false)

            val result = controller.createMcpServer(request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to create MCP server", result.message)
        }

        @Test
        @DisplayName("createMcpServer - service 抛异常时返回错误")
        fun `createMcpServer should return error on service exception`() {
            val request = McpServerCreateRequest(
                name = "dup-mcp",
                type = "sse",
                url = "http://localhost:3000/sse",
            )
            `when`(mcpServerService.createMcpServer(any()))
                .thenThrow(BizException("MCP name already exists"))

            val result = controller.createMcpServer(request)

            assertFalse(result.isSuccess())
            assertEquals("MCP name already exists", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/mcp/update/{id}")
    inner class UpdateEndpoint {

        private val request = McpServerUpdateRequest(
            id = 1L,
            name = "updated-mcp",
            type = "streamablehttp",
            url = "http://localhost:3001/mcp",
        )

        @Test
        @DisplayName("updateMcpServer - 更新成功")
        fun `updateMcpServer should return success`() {
            `when`(mcpServerService.updateMcpServer(any(), any())).thenReturn(true)

            val result = controller.updateMcpServer(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("updateMcpServer - 更新失败时返回错误")
        fun `updateMcpServer should return error when service returns false`() {
            `when`(mcpServerService.updateMcpServer(any(), any())).thenReturn(false)

            val result = controller.updateMcpServer(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update MCP server", result.message)
        }

        @Test
        @DisplayName("updateMcpServer - service 抛异常时返回错误")
        fun `updateMcpServer should return error on service exception`() {
            `when`(mcpServerService.updateMcpServer(any(), any()))
                .thenThrow(BizException("MCP server not found"))

            val result = controller.updateMcpServer(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("MCP server not found", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/mcp/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggleMcpServer - 切换成功")
        fun `toggleMcpServer should return success`() {
            `when`(mcpServerService.toggleMcpServerStatus(1L, 1)).thenReturn(true)

            val result = controller.toggleMcpServer(1L, 1)

            assertTrue(result.isSuccess())
            verify(mcpServerService).toggleMcpServerStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleMcpServer - 切换失败时返回错误")
        fun `toggleMcpServer should return error when service returns false`() {
            `when`(mcpServerService.toggleMcpServerStatus(1L, 0)).thenReturn(false)

            val result = controller.toggleMcpServer(1L, 0)

            assertFalse(result.isSuccess())
            assertEquals("Failed to toggle status", result.message)
        }

        @Test
        @DisplayName("toggleMcpServer - service 抛异常时返回错误")
        fun `toggleMcpServer should return error on service exception`() {
            `when`(mcpServerService.toggleMcpServerStatus(999L, 1))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.toggleMcpServer(999L, 1)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/mcp/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("deleteMcpServer - 删除成功")
        fun `deleteMcpServer should return success`() {
            `when`(mcpServerService.deleteMcpServer(1L)).thenReturn(true)

            val result = controller.deleteMcpServer(1L)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("deleteMcpServer - 删除失败时返回错误")
        fun `deleteMcpServer should return error when service returns false`() {
            `when`(mcpServerService.deleteMcpServer(999L)).thenReturn(false)

            val result = controller.deleteMcpServer(999L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete MCP server", result.message)
        }

        @Test
        @DisplayName("deleteMcpServer - service 抛异常时返回错误")
        fun `deleteMcpServer should return error on service exception`() {
            `when`(mcpServerService.deleteMcpServer(1L))
                .thenThrow(BizException("MCP server is in use"))

            val result = controller.deleteMcpServer(1L)

            assertFalse(result.isSuccess())
            assertEquals("MCP server is in use", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/mcp/{id}/connectivity-test")
    inner class ConnectivityTestEndpoint {

        @Test
        @DisplayName("connectivityTest - 连接成功返回 true")
        fun `connectivityTest should return true when connection ok`() {
            `when`(mcpServerService.connectivityTest(1L)).thenReturn(true)

            val result = controller.connectivityTest(1L)

            assertTrue(result.isSuccess())
            assertEquals(true, result.data)
        }

        @Test
        @DisplayName("connectivityTest - 连接失败返回 false")
        fun `connectivityTest should return false when connection fails`() {
            `when`(mcpServerService.connectivityTest(1L)).thenReturn(false)

            val result = controller.connectivityTest(1L)

            assertTrue(result.isSuccess())
            assertEquals(false, result.data)
        }

        @Test
        @DisplayName("connectivityTest - service 抛异常时返回错误")
        fun `connectivityTest should return error on service exception`() {
            `when`(mcpServerService.connectivityTest(1L))
                .thenThrow(RuntimeException("Connection timeout"))

            val result = controller.connectivityTest(1L)

            assertFalse(result.isSuccess())
            assertEquals("Connection timeout", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/mcp/{id}/list_tools")
    inner class ListToolsEndpoint {

        @Test
        @DisplayName("listTools - 返回工具列表并解析参数")
        fun `listTools should return tools with parameters`() {
            val inputSchema = McpSchema.JsonSchema(
                "object",
                mapOf<String, Any>(
                    "file_path" to mapOf("type" to "string", "description" to "File path"),
                ),
                null,
                null,
                null,
                null,
            )
            val tool = McpSchema.Tool(
                "read_file",
                null,
                "Read a file",
                inputSchema,
                null,
                null,
                null,
            )
            `when`(mcpServerService.listTools(1L)).thenReturn(listOf(tool))

            val result = controller.listTools(1L)

            assertTrue(result.isSuccess())
            assertEquals(1, result.data?.size)
            assertEquals("read_file", result.data?.get(0)?.name)
            assertEquals(1, result.data?.get(0)?.parameters?.size)
            assertEquals("file_path", result.data?.get(0)?.parameters?.get(0)?.name)
            assertEquals("string", result.data?.get(0)?.parameters?.get(0)?.type)
            assertEquals("File path", result.data?.get(0)?.parameters?.get(0)?.description)
        }

        @Test
        @DisplayName("listTools - properties 为 null 时返回空参数列表")
        fun `listTools should return empty parameters when properties is null`() {
            val inputSchema = McpSchema.JsonSchema("object", null, null, null, null, null)
            val tool = McpSchema.Tool("no_param_tool", null, "No params", inputSchema, null, null, null)
            `when`(mcpServerService.listTools(1L)).thenReturn(listOf(tool))

            val result = controller.listTools(1L)

            assertTrue(result.isSuccess())
            assertEquals(1, result.data?.size)
            assertTrue(result.data?.get(0)?.parameters?.isEmpty() == true)
        }

        @Test
        @DisplayName("listTools - 工具列表为空时返回空列表")
        fun `listTools should return empty list when no tools`() {
            `when`(mcpServerService.listTools(1L)).thenReturn(emptyList())

            val result = controller.listTools(1L)

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("listTools - service 抛异常时返回错误")
        fun `listTools should return error on service exception`() {
            `when`(mcpServerService.listTools(999L))
                .thenThrow(RuntimeException("MCP server unreachable"))

            val result = controller.listTools(999L)

            assertFalse(result.isSuccess())
            assertEquals("MCP server unreachable", result.message)
        }
    }
}
