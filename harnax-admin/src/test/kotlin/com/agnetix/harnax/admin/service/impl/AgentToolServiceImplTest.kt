package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.AgentToolCreateRequest
import com.agnetix.harnax.admin.dto.AgentToolUpdateRequest
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.mapper.AgentToolMapper
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
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

/**
 * AgentToolServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper layer dependencies
 *
 * @author agnetix
 * @since 2026-05-16
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentToolServiceImplTest {

    @Mock
    private lateinit var agentToolMapper: AgentToolMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var objectMapper: ObjectMapper

    @Mock
    private lateinit var secretFieldEncryptor: SecretFieldEncryptor

    @InjectMocks
    private lateinit var agentToolService: AgentToolServiceImpl

    private lateinit var testAgentTool: AgentTool

    @BeforeEach
    fun setUp() {
        // Initialize test data
        testAgentTool = AgentTool().apply {
            id = 1L
            name = "time-tool-box"
            displayName = "时间工具"
            type = "BUILTIN"
            beanName = "time-tool-box"
            needConfirm = 0
            status = 1
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
            val agentTools = listOf(testAgentTool)
            `when`(agentToolMapper.selectAgentToolList(null, null, null, "admin")).thenReturn(agentTools)

            // When
            val page = agentToolService.page(null, null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(agentToolMapper).selectAgentToolList(null, null, null, "admin")
        }

        @Test
        @DisplayName("page - Filter by keyword")
        fun `page should filter by keyword`() {
            // Given
            val filteredTools = listOf(testAgentTool)
            `when`(agentToolMapper.selectAgentToolList("time", null, null, "admin")).thenReturn(filteredTools)

            // When
            val page = agentToolService.page("time", null, null, 1, 10)

            // Then
            assertNotNull(page)
            verify(agentToolMapper).selectAgentToolList("time", null, null, "admin")
        }

        @Test
        @DisplayName("page - Filter by type")
        fun `page should filter by type`() {
            // Given
            val filteredTools = listOf(testAgentTool)
            `when`(agentToolMapper.selectAgentToolList(null, null, "BUILTIN", "admin")).thenReturn(filteredTools)

            // When
            val page = agentToolService.page(null, null, "BUILTIN", 1, 10)

            // Then
            assertNotNull(page)
            verify(agentToolMapper).selectAgentToolList(null, null, "BUILTIN", "admin")
        }
    }

    @Nested
    @DisplayName("Get Agent Tool Tests")
    inner class GetAgentToolTests {

        @Test
        @DisplayName("getAgentTool - Query by ID successfully")
        fun `getAgentTool should return tool by id`() {
            // Given
            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)

            // When
            val result = agentToolService.getAgentTool(1L)

            // Then
            assertNotNull(result)
            assertEquals("time-tool-box", result?.name)
            verify(agentToolMapper).selectById(1L)
        }

        @Test
        @DisplayName("getAgentTool - Return null when not exists")
        fun `getAgentTool should return null when not exists`() {
            // Given
            `when`(agentToolMapper.selectById(999L)).thenReturn(null)

            // When
            val result = agentToolService.getAgentTool(999L)

            // Then
            assertNull(result)
            verify(agentToolMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("Create Agent Tool Tests")
    inner class CreateAgentToolTests {

        @Test
        @DisplayName("createAgentTool - Create BUILTIN type successfully")
        fun `createAgentTool should create BUILTIN type successfully`() {
            // Given
            val request = AgentToolCreateRequest(
                name = "time-tool-box",
                displayName = "时间工具",
                type = "BUILTIN",
                beanName = "time-tool-box",
            )

            `when`(agentToolMapper.insert(any())).thenReturn(1)

            // When
            val result = agentToolService.createAgentTool(request)

            // Then
            assertTrue(result)
            verify(agentToolMapper).insert(any())
        }

        @Test
        @DisplayName("createAgentTool - Create HTTP type successfully")
        fun `createAgentTool should create HTTP type successfully`() {
            // Given
            val request = AgentToolCreateRequest(
                name = "http-tool",
                displayName = "HTTP工具",
                type = "HTTP",
                httpUrl = "http://localhost:8080/api/tool",
                inputSchema = """{"type":"object","properties":{"query":{"type":"string"}}}""",
            )

            `when`(agentToolMapper.insert(any())).thenReturn(1)

            // When
            val result = agentToolService.createAgentTool(request)

            // Then
            assertTrue(result)
            verify(agentToolMapper).insert(any())
        }

        @Test
        @DisplayName("createAgentTool - Create with needConfirm=1")
        fun `createAgentTool should create with needConfirm=1`() {
            // Given
            val request = AgentToolCreateRequest(
                name = "dangerous-tool",
                displayName = "危险工具",
                type = "BUILTIN",
                beanName = "dangerous-tool",
                needConfirm = 1,
            )

            `when`(agentToolMapper.insert(any())).thenReturn(1)

            // When
            val result = agentToolService.createAgentTool(request)

            // Then
            assertTrue(result)
            verify(agentToolMapper).insert(any())
        }
    }

    @Nested
    @DisplayName("Update Agent Tool Tests")
    inner class UpdateAgentToolTests {

        @Test
        @DisplayName("updateAgentTool - Update partial fields successfully")
        fun `updateAgentTool should update partial fields successfully`() {
            // Given
            val request = AgentToolUpdateRequest(
                displayName = "更新后的工具",
                description = "Updated description",
            )

            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)
            `when`(agentToolMapper.updateById(any())).thenReturn(1)

            // When
            val result = agentToolService.updateAgentTool(1L, request)

            // Then
            assertTrue(result)
            verify(agentToolMapper).selectById(1L)
            verify(agentToolMapper).updateById(any())
        }

        @Test
        @DisplayName("updateAgentTool - Throw RuntimeException when tool not found")
        fun `updateAgentTool should throw RuntimeException when tool not found`() {
            // Given
            val request = AgentToolUpdateRequest(name = "Test")

            `when`(agentToolMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                agentToolService.updateAgentTool(999L, request)
            }
            assertTrue(exception.message!!.contains("Agent tool not found"))
            verify(agentToolMapper, never()).updateById(any())
        }
    }

    @Nested
    @DisplayName("Toggle Status Tests")
    inner class ToggleStatusTests {

        @Test
        @DisplayName("toggleAgentToolStatus - Disable tool successfully")
        fun `toggleAgentToolStatus should disable tool successfully`() {
            // Given
            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)
            `when`(agentToolMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = agentToolService.toggleAgentToolStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(agentToolMapper).selectById(1L)
            verify(agentToolMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleAgentToolStatus - Enable tool successfully")
        fun `toggleAgentToolStatus should enable tool successfully`() {
            // Given
            `when`(agentToolMapper.selectById(1L)).thenReturn(testAgentTool)
            `when`(agentToolMapper.updateStatus(1L, 1)).thenReturn(1)

            // When
            val result = agentToolService.toggleAgentToolStatus(1L, 1)

            // Then
            assertTrue(result)
            verify(agentToolMapper).updateStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleAgentToolStatus - Throw RuntimeException when tool not found")
        fun `toggleAgentToolStatus should throw when tool not found`() {
            // Given
            `when`(agentToolMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                agentToolService.toggleAgentToolStatus(999L, 0)
            }
            assertTrue(exception.message!!.contains("Agent tool not found"))
            verify(agentToolMapper, never()).updateStatus(anyLong(), anyInt())
        }
    }

    @Nested
    @DisplayName("Delete Agent Tool Tests")
    inner class DeleteAgentToolTests {

        @Test
        @DisplayName("deleteAgentTool - Logically delete successfully")
        fun `deleteAgentTool should logically delete successfully`() {
            // Given
            `when`(agentToolMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = agentToolService.deleteAgentTool(1L)

            // Then
            assertTrue(result)
            verify(agentToolMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteAgentTool - Return false when not found")
        fun `deleteAgentTool should return false when not found`() {
            // Given
            `when`(agentToolMapper.deleteById(999L)).thenReturn(0)

            // When
            val result = agentToolService.deleteAgentTool(999L)

            // Then
            assertFalse(result)
            verify(agentToolMapper).deleteById(999L)
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Convert entity to response")
        fun `convertToResponse should convert entity correctly`() {
            // When
            val result = agentToolService.convertToResponse(testAgentTool)

            // Then
            assertNotNull(result)
            assertEquals(testAgentTool.id, result.id)
            assertEquals(testAgentTool.name, result.name)
            assertEquals(testAgentTool.displayName, result.displayName)
            assertEquals(testAgentTool.type, result.type)
            assertEquals(testAgentTool.beanName, result.beanName)
            assertEquals(testAgentTool.status, result.status)
        }
    }

    @Nested
    @DisplayName("Get Available Tools Tests")
    inner class GetAvailableToolsTests {

        @Test
        @DisplayName("getAvailableTools - Return all enabled tools")
        fun `getAvailableTools should return all enabled tools`() {
            // Given
            val enabledTools = listOf(testAgentTool)
            `when`(agentToolMapper.selectAllEnabled()).thenReturn(enabledTools)

            // When
            val result = agentToolService.getAvailableTools()

            // Then
            assertNotNull(result)
            assertEquals(1, result.size)
            assertEquals("time-tool-box", result[0].name)
            verify(agentToolMapper).selectAllEnabled()
        }
    }
}
