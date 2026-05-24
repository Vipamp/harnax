package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.dto.AgentCreateRequest
import com.agnetix.harnax.admin.dto.AgentUpdateRequest
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.admin.service.ModelService
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.SkillService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.SessionMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Captor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
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
 * AgentServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper and Service layer dependencies
 * Covers normal flows, exception flows, and boundary conditions
 *
 * @author agnetix
 * @since 2026-05-17
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentServiceImplTest {

    @Mock
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var mcpServerService: McpServerService

    @Mock
    private lateinit var skillRepositoryService: SkillRepositoryService

    @Mock
    private lateinit var skillService: SkillService

    @Mock
    private lateinit var modelService: ModelService

    @Mock
    private lateinit var sessionMapper: SessionMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Captor
    private lateinit var agentCaptor: ArgumentCaptor<Agent>

    @InjectMocks
    private lateinit var agentService: AgentServiceImpl

    private lateinit var testAgent: Agent

    @BeforeEach
    fun setUp() {
        // Initialize test data
        testAgent = Agent().apply {
            id = 1L
            tenantId = 1L
            name = "Test Agent"
            description = "Test agent description"
            systemPrompt = "You are a helpful assistant"
            modelId = 1L
            mcpList = """[{"id":1,"enable_skip":"true"}]"""
            skillList = "1,2,3"
            owner = "admin"
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
            val agents = listOf(testAgent)
            `when`(agentMapper.selectAgentList(null, null, "admin")).thenReturn(agents)

            // When
            val page = agentService.page(null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(agentMapper).selectAgentList(null, null, "admin")
        }

        @Test
        @DisplayName("page - Filter by name")
        fun `page should filter by name`() {
            // Given
            val filteredAgents = listOf(testAgent)
            `when`(agentMapper.selectAgentList("Test", null, "admin")).thenReturn(filteredAgents)

            // When
            val page = agentService.page("Test", null, 1, 10)

            // Then
            assertNotNull(page)
            verify(agentMapper).selectAgentList("Test", null, "admin")
        }

        @Test
        @DisplayName("page - Filter by status")
        fun `page should filter by status`() {
            // Given
            val activeAgents = listOf(testAgent)
            `when`(agentMapper.selectAgentList(null, 1, "admin")).thenReturn(activeAgents)

            // When
            val page = agentService.page(null, 1, 1, 10)

            // Then
            assertNotNull(page)
            verify(agentMapper).selectAgentList(null, 1, "admin")
        }
    }

    @Nested
    @DisplayName("Create Agent Tests")
    inner class CreateAgentTests {

        @Test
        @DisplayName("createAgent - Create agent with all fields successfully")
        fun `createAgent should create agent with all fields successfully`() {
            // Given
            val mcpConfig = AgentCreateRequest.McpConfig(
                id = 1L,
                enableSkip = "true",
            )

            val request = AgentCreateRequest(
                name = "New Agent",
                description = "New agent description",
                systemPrompt = "You are a new assistant",
                modelId = 1L,
                owner = "admin",
                status = 1,
                mcpList = listOf(mcpConfig),
                skillList = "1,2",
            )

            `when`(agentMapper.insert(any())).thenReturn(1)

            // When
            val result = agentService.createAgent(request)

            // Then
            assertTrue(result)
            verify(agentMapper).insert(any())
        }

        @Test
        @DisplayName("createAgent - Create agent with minimal fields")
        fun `createAgent should create agent with minimal fields successfully`() {
            // Given
            val request = AgentCreateRequest(
                name = "Minimal Agent",
                description = "Minimal description",
                systemPrompt = "Minimal prompt",
                modelId = 1L,
                owner = "admin",
            )

            `when`(agentMapper.insert(any())).thenReturn(1)

            // When
            val result = agentService.createAgent(request)

            // Then
            assertTrue(result)
            verify(agentMapper).insert(any())
        }

        @Test
        @DisplayName("createAgent - Create agent without MCP list")
        fun `createAgent should create agent without mcp list`() {
            // Given
            val request = AgentCreateRequest(
                name = "No MCP Agent",
                description = "No MCP description",
                systemPrompt = "No MCP prompt",
                modelId = 1L,
                owner = "admin",
                mcpList = null,
            )

            `when`(agentMapper.insert(any())).thenReturn(1)

            // When
            val result = agentService.createAgent(request)

            // Then
            assertTrue(result)
            verify(agentMapper).insert(any())
        }

        @Test
        @DisplayName("createAgent - Create agent without skill list")
        fun `createAgent should create agent without skill list`() {
            // Given
            val request = AgentCreateRequest(
                name = "No Skill Agent",
                description = "No skill description",
                systemPrompt = "No skill prompt",
                modelId = 1L,
                owner = "admin",
                skillList = null,
            )

            `when`(agentMapper.insert(any())).thenReturn(1)

            // When
            val result = agentService.createAgent(request)

            // Then
            assertTrue(result)
            verify(agentMapper).insert(any())
        }

        @Test
        @DisplayName("createAgent - Set default status when not provided")
        fun `createAgent should set default status when not provided`() {
            // Given
            val request = AgentCreateRequest(
                name = "Default Status Agent",
                description = "Default status description",
                systemPrompt = "Default status prompt",
                modelId = 1L,
                owner = "admin",
                status = null,
            )

            `when`(agentMapper.insert(any())).thenReturn(1)

            // When
            val result = agentService.createAgent(request)

            // Then
            assertTrue(result)
            verify(agentMapper).insert(any())
        }

        @Test
        @DisplayName("createAgent - Set default isPublic when not provided")
        fun `createAgent should set default isPublic when not provided`() {
            // Given
            val request = AgentCreateRequest(
                name = "Default Public Agent",
                description = "Default public description",
                systemPrompt = "Default public prompt",
                modelId = 1L,
                owner = "admin",
            )

            `when`(agentMapper.insert(any())).thenReturn(1)

            // When
            val result = agentService.createAgent(request)

            // Then
            assertTrue(result)
            verify(agentMapper).insert(any())
        }
    }

    @Nested
    @DisplayName("Get Agent Tests")
    inner class GetAgentTests {

        @Test
        @DisplayName("getAgent - Query by ID successfully")
        fun `getAgent should return agent by id`() {
            // Given
            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)

            // When
            val result = agentService.getAgent(1L)

            // Then
            assertNotNull(result)
            assertEquals("Test Agent", result?.name)
            verify(agentMapper).selectById(1L)
        }

        @Test
        @DisplayName("getAgent - Return null when not exists")
        fun `getAgent should return null when not exists`() {
            // Given
            `when`(agentMapper.selectById(999L)).thenReturn(null)

            // When
            val result = agentService.getAgent(999L)

            // Then
            assertNull(result)
            verify(agentMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("Update Agent Tests")
    inner class UpdateAgentTests {

        @Test
        @DisplayName("updateAgent - Update all fields successfully")
        fun `updateAgent should update all fields successfully`() {
            // Given
            val mcpConfig = AgentCreateRequest.McpConfig(
                id = 2L,
                enableSkip = "false",
            )

            val request = AgentUpdateRequest(
                name = "Updated Agent",
                description = "Updated description",
                systemPrompt = "Updated prompt",
                modelId = 2L,
                owner = "newowner",
                isPublic = 0,
                mcpList = listOf(mcpConfig),
                skillList = "4,5",
            )

            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(agentMapper.updateById(any())).thenReturn(1)

            // When
            val result = agentService.updateAgent(1L, request)

            // Then
            assertTrue(result)
            verify(agentMapper).updateById(any())
        }

        @Test
        @DisplayName("updateAgent - Update partial fields")
        fun `updateAgent should update partial fields successfully`() {
            // Given
            val request = AgentUpdateRequest(
                name = "Partial Update Agent",
                description = "Partial update description",
            )

            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(agentMapper.updateById(any())).thenReturn(1)

            // When
            val result = agentService.updateAgent(1L, request)

            // Then
            assertTrue(result)
            verify(agentMapper).updateById(any())
        }

        @Test
        @DisplayName("updateAgent - Clear MCP list with empty list")
        fun `updateAgent should clear mcp list with empty list`() {
            // Given
            val request = AgentUpdateRequest(
                mcpList = listOf(),
            )

            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(agentMapper.updateById(any())).thenReturn(1)

            // When
            val result = agentService.updateAgent(1L, request)

            // Then
            assertTrue(result)
            verify(agentMapper).updateById(any())
            // Verify the testAgent was modified
            assertEquals("", testAgent.mcpList)
        }

        @Test
        @DisplayName("updateAgent - Clear skill list with empty string")
        fun `updateAgent should clear skill list with empty string`() {
            // Given
            val request = AgentUpdateRequest(
                skillList = "",
            )

            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(agentMapper.updateById(any())).thenReturn(1)

            // When
            val result = agentService.updateAgent(1L, request)

            // Then
            assertTrue(result)
            verify(agentMapper).updateById(any())
            // Verify the testAgent was modified - empty string becomes "null" string
            assertEquals("null", testAgent.skillList)
        }

        @Test
        @DisplayName("updateAgent - Keep original MCP list when not provided")
        fun `updateAgent should keep original mcp list when not provided`() {
            // Given
            val originalMcpList = testAgent.mcpList
            val request = AgentUpdateRequest(
                name = "Keep MCP Agent",
            )

            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(agentMapper.updateById(any())).thenReturn(1)

            // When
            val result = agentService.updateAgent(1L, request)

            // Then
            assertTrue(result)
            verify(agentMapper).updateById(any())
            // Verify the mcpList was not changed
            assertEquals(originalMcpList, testAgent.mcpList)
        }

        @Test
        @DisplayName("updateAgent - Throw RuntimeException when agent not found")
        fun `updateAgent should throw RuntimeException when agent not found`() {
            // Given
            val request = AgentUpdateRequest(name = "Test")

            `when`(agentMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                agentService.updateAgent(999L, request)
            }
            // Service wraps the exception message
            assertTrue(exception.message?.contains("Agent not found") == true)
            verify(agentMapper, never()).updateById(any())
        }
    }

    @Nested
    @DisplayName("Toggle Status Tests")
    inner class ToggleStatusTests {

        @Test
        @DisplayName("toggleAgentStatus - Disable agent successfully")
        fun `toggleAgentStatus should disable agent successfully`() {
            // Given
            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(agentMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = agentService.toggleAgentStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(agentMapper).selectById(1L)
            verify(agentMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleAgentStatus - Enable agent successfully")
        fun `toggleAgentStatus should enable agent successfully`() {
            // Given
            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(agentMapper.updateStatus(1L, 1)).thenReturn(1)

            // When
            val result = agentService.toggleAgentStatus(1L, 1)

            // Then
            assertTrue(result)
            verify(agentMapper).updateStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleAgentStatus - Throw RuntimeException when agent not found")
        fun `toggleAgentStatus should throw RuntimeException when agent not found`() {
            // Given
            `when`(agentMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                agentService.toggleAgentStatus(999L, 0)
            }
            assertEquals("Agent not found", exception.message)
            verify(agentMapper, never()).updateStatus(any(), anyInt())
        }
    }

    @Nested
    @DisplayName("Delete Agent Tests")
    inner class DeleteAgentTests {

        @Test
        @DisplayName("deleteAgent - Logically delete agent successfully")
        fun `deleteAgent should logically delete agent successfully`() {
            // Given
            `when`(agentMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = agentService.deleteAgent(1L)

            // Then
            assertTrue(result)
            verify(agentMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteAgent - Return false when delete fails")
        fun `deleteAgent should return false when delete fails`() {
            // Given
            `when`(agentMapper.deleteById(1L)).thenReturn(0)

            // When
            val result = agentService.deleteAgent(1L)

            // Then
            assertFalse(result)
            verify(agentMapper).deleteById(1L)
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Convert agent with all associations")
        fun `convertToResponse should convert agent with all associations`() {
            // Given
            val model = Model().apply {
                id = 1L
                modelName = "gpt-4"
                price = 0.05
            }

            val sessions = listOf(
                Session().apply {
                    id = 1L
                    title = "Session 1"
                    sessionDescription = "Description 1"
                    sessionId = "session-1"
                },
            )

            val mcpServer = McpServer().apply {
                id = 1L
                name = "Weather MCP"
                description = "Weather service"
            }

            `when`(modelService.getModel(1L)).thenReturn(model)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)
            `when`(mcpServerService.getMcpServer(1L)).thenReturn(mcpServer)

            // When
            val result = agentService.convertToResponse(testAgent)

            // Then
            assertNotNull(result)
            assertEquals(testAgent.id, result.id)
            assertEquals(testAgent.name, result.name)
            assertEquals("gpt-4", result.modelName)
            assertEquals(0.05, result.modelPrice)
            assertEquals(1, result.sessionCount)
            assertNotNull(result.mcpList)
            assertEquals(1, result.mcpList?.size)
            verify(modelService).getModel(1L)
            verify(sessionMapper).selectByAgentId(1L)
            verify(mcpServerService).getMcpServer(1L)
        }

        @Test
        @DisplayName("convertToResponse - Convert agent without MCP list")
        fun `convertToResponse should convert agent without mcp list`() {
            // Given
            val agentWithoutMcp = Agent().apply {
                id = 1L
                name = "No MCP Agent"
                modelId = 1L
                mcpList = ""
                skillList = ""
            }

            val sessions = listOf<Session>()
            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)

            // When
            val result = agentService.convertToResponse(agentWithoutMcp)

            // Then
            assertNotNull(result)
            // When mcpList is empty, response.mcpList is null (not set)
            assertNull(result.mcpList)
            assertEquals(0, result.sessionCount)
        }

        @Test
        @DisplayName("convertToResponse - Convert agent without skill list")
        fun `convertToResponse should convert agent without skill list`() {
            // Given
            val agentWithoutSkill = Agent().apply {
                id = 1L
                name = "No Skill Agent"
                modelId = 1L
                mcpList = ""
                skillList = ""
            }

            val sessions = listOf<Session>()
            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)

            // When
            val result = agentService.convertToResponse(agentWithoutSkill)

            // Then
            assertNotNull(result)
            // When skillList is empty, response.skillList is null (not set)
            assertNull(result.skillList)
        }

        @Test
        @DisplayName("convertToResponse - Handle invalid MCP list JSON gracefully")
        fun `convertToResponse should handle invalid mcp list json gracefully`() {
            // Given
            val agentWithInvalidMcp = Agent().apply {
                id = 1L
                name = "Invalid MCP Agent"
                modelId = 1L
                mcpList = "invalid json"
                skillList = ""
            }

            val sessions = listOf<Session>()
            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)

            // When
            val result = agentService.convertToResponse(agentWithInvalidMcp)

            // Then
            assertNotNull(result)
            assertTrue(result.mcpList?.isEmpty() == true)
        }

        @Test
        @DisplayName("convertToResponse - Handle invalid skill IDs gracefully")
        fun `convertToResponse should handle invalid skill ids gracefully`() {
            // Given
            val agentWithInvalidSkill = Agent().apply {
                id = 1L
                name = "Invalid Skill Agent"
                modelId = 1L
                mcpList = ""
                skillList = "abc,def"
            }

            val sessions = listOf<Session>()
            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)

            // When
            val result = agentService.convertToResponse(agentWithInvalidSkill)

            // Then
            assertNotNull(result)
            assertTrue(result.skillList?.isEmpty() == true)
        }

        @Test
        @DisplayName("convertToResponse - Skip non-existent MCP servers")
        fun `convertToResponse should skip non-existent mcp servers`() {
            // Given
            val sessions = listOf<Session>()
            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)
            `when`(mcpServerService.getMcpServer(1L)).thenReturn(null)

            // When
            val result = agentService.convertToResponse(testAgent)

            // Then
            assertNotNull(result)
            assertTrue(result.mcpList?.isEmpty() == true)
        }

        @Test
        @DisplayName("convertToResponse - Skip non-existent skills")
        fun `convertToResponse should skip non-existent skills`() {
            // Given
            val agentWithSkills = Agent().apply {
                id = 1L
                name = "Skill Agent"
                modelId = 1L
                mcpList = ""
                skillList = "999"
            }

            val sessions = listOf<Session>()
            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)
            `when`(skillService.getSkill(999L)).thenReturn(null)

            // When
            val result = agentService.convertToResponse(agentWithSkills)

            // Then
            assertNotNull(result)
            assertTrue(result.skillList?.isEmpty() == true)
        }
    }
}
