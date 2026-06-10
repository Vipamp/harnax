package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.McpServerCreateRequest
import com.agnetix.harnax.admin.dto.McpServerUpdateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.mapper.McpServerMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.*
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

/**
 * McpServerServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper layer dependencies
 *
 * @author agnetix
 * @since 2026-05-16
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpServerServiceImplTest {

    @Mock
    private lateinit var mcpServerMapper: McpServerMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @InjectMocks
    private lateinit var mcpServerService: McpServerServiceImpl

    private lateinit var testMcpServer: McpServer

    @BeforeEach
    fun setUp() {
        // Initialize test data
        testMcpServer = McpServer().apply {
            id = 1L
            tenantId = 1L
            name = "Weather MCP"
            description = "Weather query service"
            type = "streamablehttp"
            command = ""
            url = "http://localhost:8081/weather"
            status = 1
            isPublic = 1
            creator = "admin"
            active = 1
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
    inner class PageQueryTests {

        @Test
        @DisplayName("page - Normal pagination query")
        fun `page should return paginated results`() {
            // Given
            val mcpServers = listOf(testMcpServer)
            `when`(mcpServerMapper.selectMcpServerList(null, null, null, "admin")).thenReturn(mcpServers)

            // When
            val page = mcpServerService.page(null, null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(mcpServerMapper).selectMcpServerList(null, null, null, "admin")
        }

        @Test
        @DisplayName("page - Filter by keyword")
        fun `page should filter by keyword`() {
            // Given
            val filteredServers = listOf(testMcpServer)
            `when`(mcpServerMapper.selectMcpServerList("Weather", null, null, "admin")).thenReturn(filteredServers)

            // When
            val page = mcpServerService.page("Weather", null, null, 1, 10)

            // Then
            assertNotNull(page)
            verify(mcpServerMapper).selectMcpServerList("Weather", null, null, "admin")
        }
    }

    @Nested
    @DisplayName("Get MCP Server Tests")
    inner class GetMcpServerTests {

        @Test
        @DisplayName("getMcpServer - Query by ID successfully")
        fun `getMcpServer should return mcp server by id`() {
            // Given
            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)

            // When
            val result = mcpServerService.getMcpServer(1L)

            // Then
            assertNotNull(result)
            assertEquals("Weather MCP", result?.name)
            verify(mcpServerMapper).selectById(1L)
        }

        @Test
        @DisplayName("getMcpServer - Return null when not exists")
        fun `getMcpServer should return null when not exists`() {
            // Given
            `when`(mcpServerMapper.selectById(999L)).thenReturn(null)

            // When
            val result = mcpServerService.getMcpServer(999L)

            // Then
            assertNull(result)
            verify(mcpServerMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("Create MCP Server Tests")
    inner class CreateMcpServerTests {

        @Test
        @DisplayName("createMcpServer - Create streamablehttp type successfully")
        fun `createMcpServer should create streamablehttp type successfully`() {
            // Given
            val request = McpServerCreateRequest(
                name = "New MCP",
                description = "New MCP server",
                type = "streamablehttp",
                url = "http://localhost:8080/mcp",
            )

            `when`(mcpServerMapper.selectByName("New MCP")).thenReturn(null)
            `when`(mcpServerMapper.insert(any())).thenReturn(1)

            // When
            val result = mcpServerService.createMcpServer(request)

            // Then
            assertTrue(result)
            verify(mcpServerMapper).selectByName("New MCP")
            verify(mcpServerMapper).insert(any())
        }

        @Test
        @DisplayName("createMcpServer - Create stdio type successfully")
        fun `createMcpServer should create stdio type successfully`() {
            // Given
            val request = McpServerCreateRequest(
                name = "Stdio MCP",
                type = "stdio",
                command = "python app.py",
            )

            `when`(mcpServerMapper.selectByName("Stdio MCP")).thenReturn(null)
            `when`(mcpServerMapper.insert(any())).thenReturn(1)

            // When
            val result = mcpServerService.createMcpServer(request)

            // Then
            assertTrue(result)
            verify(mcpServerMapper).insert(any())
        }

        @Test
        @DisplayName("createMcpServer - Throw BizException when name exists")
        fun `createMcpServer should throw BizException when name exists`() {
            // Given
            val request = McpServerCreateRequest(
                name = "Existing MCP",
                type = "streamablehttp",
                url = "http://localhost:8080",
            )

            `when`(mcpServerMapper.selectByName("Existing MCP")).thenReturn(testMcpServer)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.createMcpServer(request)
            }
            assertEquals("MCP name already exists", exception.message)
            verify(mcpServerMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createMcpServer - Throw BizException when stdio type without command")
        fun `createMcpServer should throw BizException when stdio type without command`() {
            // Given
            val request = McpServerCreateRequest(
                name = "Invalid Stdio MCP",
                type = "stdio",
            )

            `when`(mcpServerMapper.selectByName("Invalid Stdio MCP")).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.createMcpServer(request)
            }
            assertEquals("MCP server of stdio type, command cannot be empty", exception.message)
            verify(mcpServerMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createMcpServer - Throw BizException when sse type without url")
        fun `createMcpServer should throw BizException when sse type without url`() {
            // Given
            val request = McpServerCreateRequest(
                name = "Invalid SSE MCP",
                type = "sse",
            )

            `when`(mcpServerMapper.selectByName("Invalid SSE MCP")).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.createMcpServer(request)
            }
            assertEquals("MCP server of sse type, url cannot be empty", exception.message)
            verify(mcpServerMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createMcpServer - Throw BizException when unsupported type")
        fun `createMcpServer should throw BizException when unsupported type`() {
            // Given
            val request = McpServerCreateRequest(
                name = "Invalid Type MCP",
                type = "invalid",
            )

            `when`(mcpServerMapper.selectByName("Invalid Type MCP")).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.createMcpServer(request)
            }
            assertEquals("Unsupported MCP type: invalid, only supports stdio/sse/streamablehttp", exception.message)
            verify(mcpServerMapper, never()).insert(any())
        }
    }

    @Nested
    @DisplayName("Update MCP Server Tests")
    inner class UpdateMcpServerTests {

        @Test
        @DisplayName("updateMcpServer - Update partial fields successfully")
        fun `updateMcpServer should update partial fields successfully`() {
            // Given
            val request = McpServerUpdateRequest(
                name = "Updated MCP",
                description = "Updated description",
                type = "streamablehttp",
                url = "http://localhost:8080/updated",
            )

            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.selectByName("Updated MCP")).thenReturn(null)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)

            // When
            val result = mcpServerService.updateMcpServer(1L, request)

            // Then
            assertTrue(result)
            verify(mcpServerMapper).selectById(1L)
            verify(mcpServerMapper).updateById(any())
        }

        @Test
        @DisplayName("updateMcpServer - Throw BizException when MCP not found")
        fun `updateMcpServer should throw BizException when mcp not found`() {
            // Given
            val request = McpServerUpdateRequest(name = "Test")

            `when`(mcpServerMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.updateMcpServer(999L, request)
            }
            assertEquals("MCP server not found", exception.message)
            verify(mcpServerMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateMcpServer - Throw BizException when name exists")
        fun `updateMcpServer should throw BizException when name exists`() {
            // Given
            val existingMcp = McpServer().apply {
                id = 2L
                name = "Existing Name"
                type = "streamablehttp"
                url = "http://localhost:8080"
            }

            val request = McpServerUpdateRequest(name = "Existing Name")

            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.selectByName("Existing Name")).thenReturn(existingMcp)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.updateMcpServer(1L, request)
            }
            assertEquals("MCP name already exists", exception.message)
            verify(mcpServerMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateMcpServer - Throw BizException when stdio type without command")
        fun `updateMcpServer should throw BizException when stdio type without command`() {
            // Given
            val request = McpServerUpdateRequest(
                type = "stdio",
                command = "",
            )

            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.updateById(any())).thenReturn(1)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.updateMcpServer(1L, request)
            }
            assertEquals("MCP server of stdio type, command cannot be empty", exception.message)
        }
    }

    @Nested
    @DisplayName("Toggle Status Tests")
    inner class ToggleStatusTests {

        @Test
        @DisplayName("toggleMcpServerStatus - Disable MCP server successfully")
        fun `toggleMcpServerStatus should disable mcp server successfully`() {
            // Given
            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = mcpServerService.toggleMcpServerStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(mcpServerMapper).selectById(1L)
            verify(mcpServerMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleMcpServerStatus - Enable MCP server successfully")
        fun `toggleMcpServerStatus should enable mcp server successfully`() {
            // Given
            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.updateStatus(1L, 1)).thenReturn(1)

            // When
            val result = mcpServerService.toggleMcpServerStatus(1L, 1)

            // Then
            assertTrue(result)
            verify(mcpServerMapper).updateStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleMcpServerStatus - Throw BizException when MCP not found")
        fun `toggleMcpServerStatus should throw BizException when mcp not found`() {
            // Given
            `when`(mcpServerMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.toggleMcpServerStatus(999L, 0)
            }
            assertEquals("MCP server not found", exception.message)
            verify(mcpServerMapper, never()).updateStatus(anyLong(), anyInt())
        }
    }

    @Nested
    @DisplayName("Delete MCP Server Tests")
    inner class DeleteMcpServerTests {

        @Test
        @DisplayName("deleteMcpServer - Logically delete MCP server successfully")
        fun `deleteMcpServer should logically delete mcp server successfully`() {
            // Given
            `when`(mcpServerMapper.selectById(1L)).thenReturn(testMcpServer)
            `when`(mcpServerMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = mcpServerService.deleteMcpServer(1L)

            // Then
            assertTrue(result)
            verify(mcpServerMapper).selectById(1L)
            verify(mcpServerMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteMcpServer - Throw BizException when MCP not found")
        fun `deleteMcpServer should throw BizException when mcp not found`() {
            // Given
            `when`(mcpServerMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                mcpServerService.deleteMcpServer(999L)
            }
            assertEquals("MCP server not found", exception.message)
            verify(mcpServerMapper, never()).deleteById(anyLong())
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Convert entity to response")
        fun `convertToResponse should convert entity to response`() {
            // When
            val result = mcpServerService.convertToResponse(testMcpServer)

            // Then
            assertNotNull(result)
            assertEquals(testMcpServer.id, result.id)
            assertEquals(testMcpServer.name, result.name)
            assertEquals(testMcpServer.description, result.description)
            assertEquals(testMcpServer.type, result.type)
        }
    }
}
