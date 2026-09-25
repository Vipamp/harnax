package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.SessionChatUpdateRequest
import com.agnetix.harnax.admin.dto.SessionCreateRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.AgentRuntimeClient
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.admin.service.ModelService
import com.agnetix.harnax.admin.service.SkillRepositoryService
import com.agnetix.harnax.admin.service.SkillService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentMcpBinding
import com.agnetix.harnax.entity.AgentSkillBinding
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.entity.Team
import com.agnetix.harnax.entity.TeamMember
import com.agnetix.harnax.entity.TeamSkillBinding
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.TeamMapper
import com.agnetix.harnax.mapper.TeamMemberMapper
import com.agnetix.harnax.mapper.TeamSkillBindingMapper
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
import org.mockito.Mockito.inOrder
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

    @Mock
    private lateinit var teamMapper: TeamMapper

    @Mock
    private lateinit var teamMemberMapper: TeamMemberMapper

    @Mock
    private lateinit var teamSkillBindingMapper: TeamSkillBindingMapper

    @Mock
    private lateinit var teamArtifactCleaner: TeamArtifactCleaner

    @Mock
    private lateinit var agentRuntimeClient: AgentRuntimeClient

    @Mock
    private lateinit var messageUtil: MessageUtil

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

        // MessageUtil echoes the key: assertions name the bundle key, never its locale text
        `when`(messageUtil.getMessage(anyString())).thenAnswer { invocation -> invocation.arguments[0] as String }

        // Mock JwtUtil
        `when`(jwtUtil.validateToken(anyString())).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken(anyString())).thenReturn("admin")

        // Deleting a session releases its runtime state first, so every case that gets that far needs an
        // answer from the runtime. The refusal cases re-stub this to name what went wrong.
        `when`(agentRuntimeClient.clearSession(anyString())).thenReturn(ResultVo.success())
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
        teamMapper = teamMapper,
        teamMemberMapper = teamMemberMapper,
        teamSkillBindingMapper = teamSkillBindingMapper,
        teamArtifactCleaner = teamArtifactCleaner,
        agentRuntimeClient = agentRuntimeClient,
        messageUtil = messageUtil,
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
        @DisplayName("createSession - 团队会话快照来自 team 行且不写 agent_id")
        fun `createSession should snapshot a team session from the team row`() {
            // Given - 团队的主管就是 team 行，没有 agent 行可指
            val request = SessionCreateRequest(title = "Team Chat", teamId = 42L)
            `when`(sessionMapper.countByTitle("Team Chat")).thenReturn(0)
            `when`(teamMapper.selectById(42L)).thenReturn(
                Team().apply {
                    id = 42L
                    tenantId = 1L
                    name = "Research"
                    description = "Investigates"
                    systemPrompt = "你是本次协作的负责人"
                    modelId = 11L
                    status = 1
                    creator = "boss"
                    // 公开团队：可见性守卫（is_public OR creator）在私有团队上会拒绝非创建者
                    isPublic = 1
                },
            )
            `when`(teamMemberMapper.selectByTeamId(42L)).thenReturn(
                listOf(
                    TeamMember().apply {
                        teamId = 42L
                        memberAgentId = 3L
                    },
                ),
            )
            `when`(sessionMapper.insert(any())).thenReturn(1)

            // When
            assertTrue(createService().createSession(request))

            // Then
            val captor = argumentCaptor<Session>()
            verify(sessionMapper).insert(captor.capture())
            val saved = captor.firstValue
            assertNull(saved.agentId)
            assertEquals(42L, saved.teamId)
            assertEquals("Research", saved.name)
            assertEquals("Investigates", saved.description)
            assertEquals("你是本次协作的负责人", saved.systemPrompt)
            assertEquals(11L, saved.modelId)
            assertEquals("boss", saved.owner)
            verify(agentService, never()).getAgent(anyLong())
        }

        @Test
        @DisplayName("createSession - 别人的私有团队起不了会话")
        fun `createSession should refuse a private team of another user`() {
            // Given - 列表页看不到它（is_public=0 且 creator 不是自己），起会话也必须同样被挡
            val request = SessionCreateRequest(title = "Not Mine", teamId = 43L)
            `when`(sessionMapper.countByTitle("Not Mine")).thenReturn(0)
            `when`(teamMapper.selectById(43L)).thenReturn(
                Team().apply {
                    id = 43L
                    tenantId = 1L
                    name = "Someone Else"
                    systemPrompt = "私有团队的提示词"
                    modelId = 11L
                    status = 1
                    creator = "boss"
                    isPublic = 0
                },
            )

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().createSession(request)
            }
            assertTrue(exception.message?.contains("Team not found") == true)
            verify(sessionMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createSession - 没有团队又没有 agentId 被拒")
        fun `createSession should require an agent when no team is asked for`() {
            // Given - agentId 现在可空，但只有团队会话可以不带它
            val request = SessionCreateRequest(title = "Homeless")
            `when`(sessionMapper.countByTitle("Homeless")).thenReturn(0)

            // When & Then
            val exception = assertThrows<RuntimeException> {
                createService().createSession(request)
            }
            assertTrue(exception.message?.contains("Agent ID cannot be empty") == true)
            verify(sessionMapper, never()).insert(any())
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
            `when`(agentService.getAgent(200L)).thenReturn(Agent().apply { id = 200L })
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
        @DisplayName("updateSession - 团队会话不接受 agentId")
        fun `updateSession should refuse an agent on a team session`() {
            // Given - 团队会话根本没有 agent 可绑
            testSession.teamId = 42L
            val request = SessionCreateRequest(title = "Updated", agentId = 7L)

            `when`(sessionMapper.selectById(1L)).thenReturn(testSession)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateSession(1L, request)
            }
            assertTrue(exception.message?.contains("follows its team") == true)
            verify(sessionMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateSession - 不带 agentId 的更新保留原有 agent")
        fun `updateSession should keep the agent when the request names none`() {
            // Given - agentId 可空之后，缺省值不能再当成「改成空」
            val request = SessionCreateRequest(title = "Renamed")

            `when`(sessionMapper.selectById(1L)).thenReturn(testSession)
            `when`(sessionMapper.updateById(any())).thenReturn(1)

            // When
            assertTrue(createService().updateSession(1L, request))

            // Then
            val captor = argumentCaptor<Session>()
            verify(sessionMapper).updateById(captor.capture())
            assertEquals(100L, captor.firstValue.agentId)
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
            `when`(agentService.getAgent(100L)).thenReturn(testAgent)
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
            `when`(sessionMapper.selectBySessionId("web-session-1")).thenReturn(testSession)

            // When
            val result = createService().getSessionChatConfig("web-session-1")

            // Then
            assertNotNull(result)
            assertEquals("Test Session", result?.title)
        }

        @Test
        @DisplayName("getSessionChatConfig - Return null when no row resolves")
        fun `getSessionChatConfig should return null when session not found`() {
            // Given
            `when`(sessionMapper.selectBySessionId("not-exist")).thenReturn(null)

            // When
            val result = createService().getSessionChatConfig("not-exist")

            // Then
            assertNull(result)
        }

        @Test
        @DisplayName("getSessionChatConfig - Another tenant's session reads as absent")
        fun `getSessionChatConfig should return null for another tenant session`() {
            // Given — the by-id read of the same controller already refuses this shape (ownedSession)
            testSession.tenantId = 940_004L
            `when`(sessionMapper.selectBySessionId("web-session-1")).thenReturn(testSession)

            // When
            val result = createService().getSessionChatConfig("web-session-1")

            // Then
            assertNull(result, "a session of another workspace must answer as the absent one it is to this caller")
        }

        @Test
        @DisplayName("getSessionChatConfig - A disabled session is named as disabled")
        fun `getSessionChatConfig should report a disabled session as disabled`() {
            // Given — disabling is `status = 0`; deleting is `active = 0`. Only the second is absent.
            testSession.status = 0
            `when`(sessionMapper.selectBySessionId("web-session-1")).thenReturn(testSession)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().getSessionChatConfig("web-session-1")
            }
            assertEquals(403, exception.code, "a disabled session is not a missing one")
            assertEquals("error.session.disabled", exception.message)
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

            `when`(sessionMapper.selectBySessionId("web-session-1")).thenReturn(testSession)
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

            `when`(sessionMapper.selectBySessionId("web-session-1")).thenReturn(testSession)
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
        @DisplayName("updateSessionChatConfig - An unreadable sessionId answers as the named 404")
        fun `updateSessionChatConfig should throw BizException when session not found`() {
            // Given
            val request = SessionChatUpdateRequest(enableThink = true)

            `when`(sessionMapper.selectBySessionId("not-exist")).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateSessionChatConfig("not-exist", request)
            }
            assertEquals(404, exception.code)
            assertEquals("error.session.notfound", exception.message)
            verify(sessionMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateSessionChatConfig - A disabled session is named as disabled")
        fun `updateSessionChatConfig should refuse a disabled session`() {
            // Given
            val request = SessionChatUpdateRequest(enableThink = true)
            testSession.status = 0
            `when`(sessionMapper.selectBySessionId("web-session-1")).thenReturn(testSession)

            // When & Then
            val exception = assertThrows<BizException> {
                createService().updateSessionChatConfig("web-session-1", request)
            }
            assertEquals(403, exception.code)
            assertEquals("error.session.disabled", exception.message)
            verify(sessionMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateSessionChatConfig - Another tenant's session is refused as absent")
        fun `updateSessionChatConfig should refuse another tenant session`() {
            // Given
            val request = SessionChatUpdateRequest(enableThink = true)
            testSession.tenantId = 940_004L
            `when`(sessionMapper.selectBySessionId("web-session-1")).thenReturn(testSession)

            // When & Then — the same two lines an unknown sessionId produces, so the refusal cannot be
            // used to check whether a leaked id belongs to someone else
            val exception = assertThrows<BizException> {
                createService().updateSessionChatConfig("web-session-1", request)
            }
            assertEquals(404, exception.code)
            assertEquals("error.session.notfound", exception.message)
            verify(sessionMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateSessionChatConfig - Throw BizException when update fails")
        fun `updateSessionChatConfig should throw BizException when update fails`() {
            // Given
            val request = SessionChatUpdateRequest(enableThink = true)

            `when`(sessionMapper.selectBySessionId("web-session-1")).thenReturn(testSession)
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
        @DisplayName("deleteSession - 先清该会话的产物，再删会话行")
        fun `deleteSession should remove the session's artifacts before the row`() {
            // Given
            `when`(sessionMapper.selectById(1L)).thenReturn(testSession)
            `when`(sessionMapper.deleteById(1L)).thenReturn(1)

            // When
            assertTrue(createService().deleteSession(1L))

            // Then - 产物先走：对象删完才轮到行，数据库失败时留下的是"行指向缺失对象"，重试即可修复
            val order = inOrder(teamArtifactCleaner, sessionMapper)
            order.verify(teamArtifactCleaner).deleteForSession(testSession.sessionId)
            order.verify(sessionMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteSession - 运行侧释放排在本地两步之前")
        fun `deleteSession should release the runtime before anything local`() {
            // Given
            `when`(sessionMapper.selectById(1L)).thenReturn(testSession)
            `when`(sessionMapper.deleteById(1L)).thenReturn(1)

            // When
            assertTrue(createService().deleteSession(1L))

            // Then - 释放按 sessionId 指名，因为那是运行态的键；行 id 对它没有意义
            val order = inOrder(agentRuntimeClient, teamArtifactCleaner, sessionMapper)
            order.verify(agentRuntimeClient).clearSession(testSession.sessionId)
            order.verify(teamArtifactCleaner).deleteForSession(testSession.sessionId)
            order.verify(sessionMapper).deleteById(1L)
        }

        @Test
        @DisplayName("deleteSession - 运行侧未能释放时保留会话")
        fun `deleteSession should keep the session when the runtime cannot release it`() {
            // Given
            `when`(sessionMapper.selectById(1L)).thenReturn(testSession)
            `when`(agentRuntimeClient.clearSession(testSession.sessionId))
                .thenReturn(ResultVo.error(500, "sandbox container is busy"))

            // When & Then - 留着会话行：行没了而运行态还在，剩下的就是一份谁也指认不了的状态，比留着更坏
            val exception = assertThrows<BizException> {
                createService().deleteSession(1L)
            }
            assertTrue(
                exception.message!!.contains("sandbox container is busy"),
                "运行侧那句原因才是用户要读到的，实际: ${exception.message}",
            )
            verify(sessionMapper, never()).deleteById(anyLong())
            verify(teamArtifactCleaner, never()).deleteForSession(anyString())
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

            `when`(modelService.getVisibleModel(1L)).thenReturn(model)
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
            verify(modelService).getVisibleModel(1L)
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

            `when`(modelService.getVisibleModel(999L)).thenReturn(null)

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

            `when`(modelService.getVisibleModel(1L)).thenReturn(null)
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

            `when`(modelService.getVisibleModel(1L)).thenReturn(null)
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

            `when`(modelService.getVisibleModel(1L)).thenReturn(null)
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

        @Test
        @DisplayName("convertToResponse - 团队会话的技能来自 team_skill_binding")
        fun `convertToResponse should read a team session from the team side`() {
            // Given - 团队会话没有 agent_id，按 agent 读会直接查不到或查错人
            val session = Session().apply {
                id = 6L
                title = "Team Session"
                agentId = null
                teamId = 42L
                modelId = 11L
            }
            `when`(teamSkillBindingMapper.selectByTeamId(42L)).thenReturn(
                listOf(
                    TeamSkillBinding().apply {
                        teamId = 42L
                        skillId = 10L
                    },
                ),
            )
            `when`(skillService.getSkill(10L)).thenReturn(
                Skill().apply {
                    id = 10L
                    name = "pdf-report"
                    repositoryId = 5L
                },
            )
            `when`(skillRepositoryService.getSkillRepository(5L)).thenReturn(
                SkillRepository().apply {
                    id = 5L
                    name = "qoder-skills"
                },
            )

            // When
            val result = createService().convertToResponse(session)

            // Then
            assertEquals(listOf("pdf-report"), result.skillList.map { it.skillName })
            assertTrue(result.mcpList.isEmpty())
            verify(skillBindingMapper, never()).selectByAgentId(anyLong())
            verify(mcpBindingMapper, never()).selectByAgentId(anyLong())
        }
    }
}
