package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.SessionChatUpdateRequest
import com.agnetix.harnax.admin.dto.SessionCreateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.admin.service.ModelService
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.SkillService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentMcpBinding
import com.agnetix.harnax.entity.AgentSkillBinding
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.SessionMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.LocalDateTime

/**
 * SessionServiceImpl Unit Tests
 * Uses Mockito to simulate Mapper and Service layer dependencies
 * Covers normal flows, exception flows, and boundary conditions
 *
 * @author agnetix
 * @since 2026-05-17
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionServiceImplTest {

    @Mock
    private lateinit var agentService: AgentService

    @Mock
    private lateinit var mcpServerService: McpServerService

    @Mock
    private lateinit var skillRepositoryService: SkillRepositoryService

    @Mock
    private lateinit var skillService: SkillService

    @Mock
    private lateinit var modelService: ModelService

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @Mock
    private lateinit var sessionMapper: SessionMapper

    @Mock
    private lateinit var mcpBindingMapper: AgentMcpBindingMapper

    @Mock
    private lateinit var skillBindingMapper: AgentSkillBindingMapper

    private lateinit var testSession: Session
    private lateinit var testAgent: Agent

    @BeforeEach
    fun setUp() {
        testSession = Session().apply {
            id = 1L
            tenantId = 1L
            title = "Test Session"
            sessionDescription = "Test session description"
            sessionId = "web-11111111-2222-3333-4444-555555555555"
            agentId = 100L
            name = "Test Agent"
            description = "Agent description"
            systemPrompt = "You are a helpful assistant"
            modelId = 1L
            enableThink = 0
            enableSearch = 0
            enablePlan = 0
            permissionMode = "DEFAULT"
            owner = "admin"
            status = 1
            isPublic = 0
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testAgent = Agent().apply {
            id = 100L
            name = "Test Agent"
            description = "Agent description"
            systemPrompt = "You are a helpful assistant"
            modelId = 1L
            owner = "admin"
            status = 1
            active = 1
        }

        // Mock HttpServletRequest for UserContextUtil
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("Authorization", "Bearer mock-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockRequest))

        // Mock JwtUtil
        `when`(jwtUtil.validateToken(anyString())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(anyString())).thenReturn("admin")
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    private fun createService(): SessionServiceImpl = SessionServiceImpl(
        agentService = agentService,
        mcpServerService = mcpServerService,
        skillRepositoryService = skillRepositoryService,
        skillService = skillService,
        modelService = modelService,
        jwtUtil = jwtUtil,
        sessionMapper = sessionMapper,
        mcpBindingMapper = mcpBindingMapper,
        skillBindingMapper = skillBindingMapper,
    )

    private fun mcpBinding(
        agentId: Long,
        mcpId: Long,
    ): AgentMcpBinding = AgentMcpBinding().apply {
        this.agentId = agentId
        this.mcpId = mcpId
    }

    private fun skillBinding(
        agentId: Long,
        skillId: Long,
    ): AgentSkillBinding = AgentSkillBinding().apply {
        this.agentId = agentId
        this.skillId = skillId
    }

    @Nested
    @DisplayName("Page Query Tests")
    inner class PageQueryTests {

        @Test
        @DisplayName("page - Normal pagination query")
        fun `page should return paginated results`() {
            // Given
            `when`(sessionMapper.selectSessionList(null, null, "admin", 1L)).thenReturn(listOf(testSession))

            // When
            val page = createService().page(null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(sessionMapper).selectSessionList(null, null, "admin", 1L)
        }

        @Test
        @DisplayName("page - Filter by keyword")
        fun `page should filter by keyword`() {
            // Given
            `when`(sessionMapper.selectSessionList("Test", null, "admin", 1L)).thenReturn(listOf(testSession))

            // When
            val page = createService().page("Test", null, 1, 10)

            // Then
            assertNotNull(page)
            verify(sessionMapper).selectSessionList("Test", null, "admin", 1L)
        }

        @Test
        @DisplayName("page - Filter by status")
        fun `page should filter by status`() {
            // Given
            `when`(sessionMapper.selectSessionList(null, 1, "admin", 1L)).thenReturn(listOf(testSession))

            // When
            val page = createService().page(null, 1, 1, 10)

            // Then
            assertNotNull(page)
            verify(sessionMapper).selectSessionList(null, 1, "admin", 1L)
        }

        @Test
        @DisplayName("page - Push the current tenant into the query")
        fun `page should filter by tenant`() {
            // Given - 不带租户就等于把别的租户公开的会话也列出来
            TenantContext.setTenantId(7L)
            try {
                `when`(sessionMapper.selectSessionList(null, null, "admin", 7L)).thenReturn(listOf(testSession))

                // When
                val page = createService().page(null, null, 1, 10)

                // Then
                assertNotNull(page)
                verify(sessionMapper).selectSessionList(null, null, "admin", 7L)
            } finally {
                TenantContext.clear()
            }
        }
    }

    @Nested
    @DisplayName("Get Session Tests")
    inner class GetSessionTests {

        @Test
        @DisplayName("getSession - Query by ID successfully")
        fun `getSession should return session by id`() {
            // Given
            `when`(sessionMapper.selectById(1L)).thenReturn(testSession)

            // When
            val result = createService().getSession(1L)

            // Then
            assertNotNull(result)
            assertEquals("Test Session", result?.title)
            verify(sessionMapper).selectById(1L)
        }

        @Test
        @DisplayName("getSession - Return null when not exists")
        fun `getSession should return null when not exists`() {
            // Given
            `when`(sessionMapper.selectById(999L)).thenReturn(null)

            // When
            val result = createService().getSession(999L)

            // Then
            assertNull(result)
            verify(sessionMapper).selectById(999L)
        }
    }

    @Nested
    @DisplayName("Create Session Tests")
    inner class CreateSessionTests {

        @Test
        @DisplayName("createSession - Create session copying agent config successfully")
        fun `createSession should create session copying agent config successfully`() {
            // Given
            val request = SessionCreateRequest(
                title = "New Session",
                sessionDescription = "New session description",
                agentId = 100L,
            )

            `when`(sessionMapper.countByTitle("New Session")).thenReturn(0)
            `when`(agentService.getAgent(100L)).thenReturn(testAgent)
            `when`(sessionMapper.insert(any())).thenReturn(1)

            // When
            val result = createService().createSession(request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Session>()
            verify(sessionMapper).insert(captor.capture())
            val saved = captor.firstValue
            assertEquals("New Session", saved.title)
            assertEquals(100L, saved.agentId)
            // Copied from agent
            assertEquals("Test Agent", saved.name)
            assertEquals("You are a helpful assistant", saved.systemPrompt)
            assertEquals(1L, saved.modelId)
            // sessionId generated with web- prefix
            assertTrue(saved.sessionId.startsWith("web-"))
            // Default status enabled and not public
            assertEquals(1, saved.status)
            assertEquals(0, saved.isPublic)
            assertEquals("admin", saved.creator)
            assertEquals(1L, saved.tenantId)
        }

        @Test
        @DisplayName("createSession - Stamp the current tenant")
        fun `createSession should stamp the current tenant`() {
            // Given - 不打标就会落到 DDL 缺省租户，和它绑定的 agent 不同租户
            val request = SessionCreateRequest(
                title = "Tenant Session",
                agentId = 100L,
            )
            `when`(sessionMapper.countByTitle("Tenant Session")).thenReturn(0)
            `when`(agentService.getAgent(100L)).thenReturn(testAgent)
            `when`(sessionMapper.insert(any())).thenReturn(1)
            TenantContext.setTenantId(7L)
            try {
                // When
                assertTrue(createService().createSession(request))

                // Then
                val captor = argumentCaptor<Session>()
                verify(sessionMapper).insert(captor.capture())
                assertEquals(7L, captor.firstValue.tenantId)
            } finally {
                TenantContext.clear()
            }
        }

        @Test
        @DisplayName("createSession - Throw RuntimeException when title already exists")
        fun `createSession should throw RuntimeException when title already exists`() {
            // Given
            val request = SessionCreateRequest(
                title = "Duplicated Session",
                agentId = 100L,
            )

            `when`(sessionMapper.countByTitle("Duplicated Session")).thenReturn(1)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().createSession(request)
            }
            // Service wraps BizException into RuntimeException
            assertTrue(exception.message?.contains("Session name already exists") == true)
            verify(sessionMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createSession - Throw RuntimeException when agent not found")
        fun `createSession should throw RuntimeException when agent not found`() {
            // Given
            val request = SessionCreateRequest(
                title = "Orphan Session",
                agentId = 999L,
            )

            `when`(sessionMapper.countByTitle("Orphan Session")).thenReturn(0)
            `when`(agentService.getAgent(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().createSession(request)
            }
            assertTrue(exception.message?.contains("Agent not found") == true)
            verify(sessionMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createSession - Return false when insert affects no rows")
        fun `createSession should return false when insert affects no rows`() {
            // Given
            val request = SessionCreateRequest(
                title = "Fail Session",
                agentId = 100L,
            )

            `when`(sessionMapper.countByTitle("Fail Session")).thenReturn(0)
            `when`(agentService.getAgent(100L)).thenReturn(testAgent)
            `when`(sessionMapper.insert(any())).thenReturn(0)

            // When
            val result = createService().createSession(request)

            // Then
            assertFalse(result)
        }

        @Test
        @DisplayName("createSession - Throw RuntimeException when insert throws")
        fun `createSession should throw RuntimeException when insert throws`() {
            // Given
            val request = SessionCreateRequest(
                title = "Error Session",
                agentId = 100L,
            )

            `when`(sessionMapper.countByTitle("Error Session")).thenReturn(0)
            `when`(agentService.getAgent(100L)).thenReturn(testAgent)
            `when`(sessionMapper.insert(any())).thenThrow(RuntimeException("db error"))

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().createSession(request)
            }
            assertTrue(exception.message?.contains("Failed to create session") == true)
        }
    }

    @Nested
    @DisplayName("Update Session Tests")
    inner class UpdateSessionTests {

        @Test
        @DisplayName("updateSession - Update session basic info successfully")
        fun `updateSession should update session basic info successfully`() {
            // Given
            val request = SessionCreateRequest(
                title = "Updated Session",
                sessionDescription = "Updated description",
                agentId = 200L,
            )

            `when`(sessionMapper.selectById(1L)).thenReturn(testSession)
            `when`(sessionMapper.updateById(any())).thenReturn(1)

            // When
            val result = createService().updateSession(1L, request)

            // Then
            assertTrue(result)
            val captor = argumentCaptor<Session>()
            verify(sessionMapper).updateById(captor.capture())
            val updated = captor.firstValue
            assertEquals("Updated Session", updated.title)
            assertEquals("Updated description", updated.description)
            assertEquals(200L, updated.agentId)
        }

        @Test
        @DisplayName("updateSession - Throw BizException when session not found")
        fun `updateSession should throw BizException when session not found`() {
            // Given
            val request = SessionCreateRequest(
                title = "Not Exists",
                agentId = 100L,
            )

            `when`(sessionMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateSession(999L, request)
            }
            assertEquals("Session not found", exception.message)
            verify(sessionMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateSession - Return false when update affects no rows")
        fun `updateSession should return false when update affects no rows`() {
            // Given
            val request = SessionCreateRequest(
                title = "No Effect",
                agentId = 100L,
            )

            `when`(sessionMapper.selectById(1L)).thenReturn(testSession)
            `when`(sessionMapper.updateById(any())).thenReturn(0)

            // When
            val result = createService().updateSession(1L, request)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("Get Session Chat Config Tests")
    inner class GetSessionChatConfigTests {

        @Test
        @DisplayName("getSessionChatConfig - Query enabled session by sessionId successfully")
        fun `getSessionChatConfig should return enabled session by sessionId`() {
            // Given
            `when`(sessionMapper.selectBySessionIdAndStatus("web-session-1", 1)).thenReturn(testSession)

            // When
            val result = createService().getSessionChatConfig("web-session-1")

            // Then
            assertNotNull(result)
            assertEquals("Test Session", result?.title)
            verify(sessionMapper).selectBySessionIdAndStatus("web-session-1", 1)
        }

        @Test
        @DisplayName("getSessionChatConfig - Return null when session not found or disabled")
        fun `getSessionChatConfig should return null when session not found or disabled`() {
            // Given
            `when`(sessionMapper.selectBySessionIdAndStatus("not-exist", 1)).thenReturn(null)

            // When
            val result = createService().getSessionChatConfig("not-exist")

            // Then
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("Update Session Chat Config Tests")
    inner class UpdateSessionChatConfigTests {

        @Test
        @DisplayName("updateSessionChatConfig - Update all config fields successfully")
        fun `updateSessionChatConfig should update all config fields successfully`() {
            // Given
            val request = SessionChatUpdateRequest(
                enableThink = true,
                enableSearch = true,
                enablePlan = true,
                permissionMode = "BYPASS",
            )

            `when`(sessionMapper.selectBySessionIdAndStatus("web-session-1", 1)).thenReturn(testSession)
            `when`(sessionMapper.updateById(any())).thenReturn(1)

            // When
            createService().updateSessionChatConfig("web-session-1", request)

            // Then
            val captor = argumentCaptor<Session>()
            verify(sessionMapper).updateById(captor.capture())
            val updated = captor.firstValue
            assertEquals(1, updated.enableThink)
            assertEquals(1, updated.enableSearch)
            assertEquals(1, updated.enablePlan)
            assertEquals("BYPASS", updated.permissionMode)
        }

        @Test
        @DisplayName("updateSessionChatConfig - Only update non-null fields")
        fun `updateSessionChatConfig should only update non-null fields`() {
            // Given
            val request = SessionChatUpdateRequest(
                enableThink = null,
                enableSearch = false,
                enablePlan = null,
                permissionMode = null,
            )

            `when`(sessionMapper.selectBySessionIdAndStatus("web-session-1", 1)).thenReturn(testSession)
            `when`(sessionMapper.updateById(any())).thenReturn(1)

            // When
            createService().updateSessionChatConfig("web-session-1", request)

            // Then
            val captor = argumentCaptor<Session>()
            verify(sessionMapper).updateById(captor.capture())
            val updated = captor.firstValue
            // enableThink is null in request, kept as original 0
            assertEquals(0, updated.enableThink)
            assertEquals(0, updated.enableSearch)
            // permissionMode is null in request, kept unchanged
            assertEquals("DEFAULT", updated.permissionMode)
        }

        @Test
        @DisplayName("updateSessionChatConfig - Throw BizException when session not found")
        fun `updateSessionChatConfig should throw BizException when session not found`() {
            // Given
            val request = SessionChatUpdateRequest(enableThink = true)

            `when`(sessionMapper.selectBySessionIdAndStatus("not-exist", 1)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateSessionChatConfig("not-exist", request)
            }
            assertEquals("Session not found or disabled", exception.message)
            verify(sessionMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateSessionChatConfig - Throw BizException when update fails")
        fun `updateSessionChatConfig should throw BizException when update fails`() {
            // Given
            val request = SessionChatUpdateRequest(enableThink = true)

            `when`(sessionMapper.selectBySessionIdAndStatus("web-session-1", 1)).thenReturn(testSession)
            `when`(sessionMapper.updateById(any())).thenReturn(0)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateSessionChatConfig("web-session-1", request)
            }
            assertEquals("Failed to update session configuration", exception.message)
        }
    }

    @Nested
    @DisplayName("Toggle Session Status Tests")
    inner class ToggleSessionStatusTests {

        @Test
        @DisplayName("toggleSessionStatus - Disable session successfully")
        fun `toggleSessionStatus should disable session successfully`() {
            // Given
            `when`(sessionMapper.selectById(1L)).thenReturn(testSession)
            `when`(sessionMapper.updateStatus(1L, 0)).thenReturn(1)

            // When
            val result = createService().toggleSessionStatus(1L, 0)

            // Then
            assertTrue(result)
            verify(sessionMapper).updateStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleSessionStatus - Enable session successfully")
        fun `toggleSessionStatus should enable session successfully`() {
            // Given
            `when`(sessionMapper.selectById(1L)).thenReturn(testSession)
            `when`(sessionMapper.updateStatus(1L, 1)).thenReturn(1)

            // When
            val result = createService().toggleSessionStatus(1L, 1)

            // Then
            assertTrue(result)
            verify(sessionMapper).updateStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleSessionStatus - Throw BizException when session not found")
        fun `toggleSessionStatus should throw BizException when session not found`() {
            // Given
            `when`(sessionMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().toggleSessionStatus(999L, 0)
            }
            assertEquals("Session not found", exception.message)
            verify(sessionMapper, never()).updateStatus(anyLong(), anyInt())
        }
    }

    @Nested
    @DisplayName("Delete Session Tests")
    inner class DeleteSessionTests {

        @Test
        @DisplayName("deleteSession - Delete session successfully")
        fun `deleteSession should delete session successfully`() {
            // Given
            `when`(sessionMapper.selectById(1L)).thenReturn(testSession)
            `when`(sessionMapper.deleteById(1L)).thenReturn(1)

            // When
            val result = createService().deleteSession(1L)

            // Then
            assertTrue(result)
            verify(sessionMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteSession - Throw BizException when session not found")
        fun `deleteSession should throw BizException when session not found`() {
            // Given
            `when`(sessionMapper.selectById(999L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().deleteSession(999L)
            }
            assertEquals("Session not found", exception.message)
            verify(sessionMapper, never()).deleteById(anyLong())
        }

        @Test
        @DisplayName("deleteSession - Return false when delete affects no rows")
        fun `deleteSession should return false when delete affects no rows`() {
            // Given
            `when`(sessionMapper.selectById(1L)).thenReturn(testSession)
            `when`(sessionMapper.deleteById(1L)).thenReturn(0)

            // When
            val result = createService().deleteSession(1L)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("Exists By Title Tests")
    inner class ExistsByTitleTests {

        @Test
        @DisplayName("existsByTitle - Return true when title exists")
        fun `existsByTitle should return true when title exists`() {
            // Given
            `when`(sessionMapper.countByTitle("Test Session")).thenReturn(1)

            // When
            val result = createService().existsByTitle("Test Session")

            // Then
            assertTrue(result)
            verify(sessionMapper).countByTitle("Test Session")
        }

        @Test
        @DisplayName("existsByTitle - Return false when title not exists")
        fun `existsByTitle should return false when title not exists`() {
            // Given
            `when`(sessionMapper.countByTitle("Not Exists")).thenReturn(0)

            // When
            val result = createService().existsByTitle("Not Exists")

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("Convert To Response Tests")
    inner class ConvertToResponseTests {

        @Test
        @DisplayName("convertToResponse - Convert session with all associations")
        fun `convertToResponse should convert session with all associations`() {
            // Given
            val model = Model().apply {
                id = 1L
                modelName = "gpt-4"
                price = 0.05
                supportReasoning = 1
                supportInternet = 1
                supportVision = 0
            }

            val mcpServer = McpServer().apply {
                id = 1L
                name = "Weather MCP"
                description = "Weather service"
            }

            val skill10 = Skill().apply {
                id = 10L
                name = "code-review"
                repositoryId = 5L
            }
            val skill20 = Skill().apply {
                id = 20L
                name = "doc-writer"
                repositoryId = 5L
            }
            val repository = SkillRepository().apply {
                id = 5L
                name = "qoder-skills"
            }

            `when`(modelService.getModel(1L)).thenReturn(model)
            `when`(mcpBindingMapper.selectByAgentId(100L)).thenReturn(listOf(mcpBinding(100L, 1L)))
            `when`(mcpServerService.getMcpServer(1L)).thenReturn(mcpServer)
            `when`(skillBindingMapper.selectByAgentId(100L))
                .thenReturn(listOf(skillBinding(100L, 10L), skillBinding(100L, 20L)))
            `when`(skillService.getSkill(10L)).thenReturn(skill10)
            `when`(skillService.getSkill(20L)).thenReturn(skill20)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(repository)

            // When
            val result = createService().convertToResponse(testSession)

            // Then
            assertNotNull(result)
            assertEquals(testSession.id, result.id)
            assertEquals("Test Session", result.title)
            assertEquals("gpt-4", result.modelName)
            assertEquals(0.05, result.modelPrice)
            assertEquals(1, result.modelSupportReasoning)
            assertEquals(1, result.modelSupportInternet)
            assertEquals(0, result.modelSupportVision)
            assertEquals(1, result.mcpList.size)
            assertEquals("Weather MCP", result.mcpList[0].mcpName)
            assertEquals(2, result.skillList.size)
            assertEquals("code-review", result.skillList[0].skillName)
            assertEquals("qoder-skills", result.skillList[0].repositoryName)
            verify(modelService).getModel(1L)
            verify(mcpServerService).getMcpServer(1L)
        }

        @Test
        @DisplayName("convertToResponse - Convert session when model not found")
        fun `convertToResponse should convert session when model not found`() {
            // Given
            val emptySession = Session().apply {
                id = 2L
                title = "Empty Session"
                modelId = 999L
            }

            `when`(modelService.getModel(999L)).thenReturn(null)

            // When
            val result = createService().convertToResponse(emptySession)

            // Then
            assertNotNull(result)
            assertNull(result.modelName)
            assertNull(result.modelPrice)
        }

        @Test
        @DisplayName("convertToResponse - Skip non-existent MCP servers")
        fun `convertToResponse should skip non-existent mcp servers`() {
            // Given
            val session = Session().apply {
                id = 3L
                title = "MCP Session"
                agentId = 100L
                modelId = 1L
            }

            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(mcpBindingMapper.selectByAgentId(100L)).thenReturn(listOf(mcpBinding(100L, 88L)))
            `when`(mcpServerService.getMcpServer(88L)).thenReturn(null)

            // When
            val result = createService().convertToResponse(session)

            // Then
            assertNotNull(result)
            assertTrue(result.mcpList.isEmpty())
        }

        @Test
        @DisplayName("convertToResponse - Return empty lists when the agent has no bindings")
        fun `convertToResponse should return empty lists when the agent has no bindings`() {
            // Given
            val session = Session().apply {
                id = 4L
                title = "No Binding Session"
                agentId = 100L
                modelId = 1L
            }

            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(mcpBindingMapper.selectByAgentId(100L)).thenReturn(emptyList())
            `when`(skillBindingMapper.selectByAgentId(100L)).thenReturn(emptyList())

            // When
            val result = createService().convertToResponse(session)

            // Then
            assertNotNull(result)
            assertTrue(result.mcpList.isEmpty())
            assertTrue(result.skillList.isEmpty())
        }

        @Test
        @DisplayName("convertToResponse - Skip orphan skill bindings")
        fun `convertToResponse should skip orphan skill bindings`() {
            // Given
            val skill10 = Skill().apply {
                id = 10L
                name = "code-review"
                repositoryId = 5L
            }

            val session = Session().apply {
                id = 5L
                title = "Bad Skill Session"
                agentId = 100L
                modelId = 1L
            }

            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(skillBindingMapper.selectByAgentId(100L))
                .thenReturn(listOf(skillBinding(100L, 999L), skillBinding(100L, 10L)))
            // binding 999 points at a deleted skill
            `when`(skillService.getSkill(999L)).thenReturn(null)
            `when`(skillService.getSkill(10L)).thenReturn(skill10)
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(null)

            // When
            val result = createService().convertToResponse(session)

            // Then
            assertNotNull(result)
            assertEquals(1, result.skillList.size)
            assertEquals("code-review", result.skillList[0].skillName)
            assertNull(result.skillList[0].repositoryName)
        }
    }
}
