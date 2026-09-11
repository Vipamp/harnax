package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.admin.dto.AgentCreateRequest
import com.agnetix.harnax.admin.dto.AgentUpdateRequest
import com.agnetix.harnax.admin.dto.EnvBinding
import com.agnetix.harnax.admin.dto.ToolConfig
import com.agnetix.harnax.admin.dto.ToolEnvParamEntry
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.*
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentMcpBinding
import com.agnetix.harnax.entity.AgentSkillBinding
import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.entity.AgentToolBinding
import com.agnetix.harnax.entity.AgentToolEnvParam
import com.agnetix.harnax.entity.EnvVariable
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.mapper.AgentCliBindingMapper
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.AgentToolBindingMapper
import com.agnetix.harnax.mapper.AgentToolEnvParamMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.CliSkillBindingMapper
import com.agnetix.harnax.mapper.McpServerMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillMapper
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
import org.mockito.kotlin.argumentCaptor
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

    @Mock
    private lateinit var agentToolService: AgentToolService

    @Mock
    private lateinit var envVariableService: EnvVariableService

    @Mock
    private lateinit var toolBindingMapper: AgentToolBindingMapper

    @Mock
    private lateinit var mcpBindingMapper: AgentMcpBindingMapper

    @Mock
    private lateinit var skillBindingMapper: AgentSkillBindingMapper

    @Mock
    private lateinit var cliBindingMapper: AgentCliBindingMapper

    @Mock
    private lateinit var cliMapper: CliMapper

    @Mock
    private lateinit var cliSkillBindingMapper: CliSkillBindingMapper

    @Mock
    private lateinit var skillMapper: SkillMapper

    @Mock
    private lateinit var mcpServerMapper: McpServerMapper

    @Mock
    private lateinit var agentToolMapper: AgentToolMapper

    @Mock
    private lateinit var agentToolEnvParamMapper: AgentToolEnvParamMapper

    @Mock
    private lateinit var secretFieldEncryptor: SecretFieldEncryptor

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

        // Bound MCP ids are now checked against live servers of the caller's tenant. The binding
        // fixtures below only assert on the rows written, so every id resolves unless a test says
        // otherwise.
        `when`(mcpServerMapper.selectByIds(any())).thenAnswer { invocation ->
            invocation.getArgument<List<Long>>(0).map { id ->
                McpServer().apply {
                    this.id = id
                    tenantId = 1L
                    name = "MCP $id"
                    type = "streamablehttp"
                }
            }
        }

        // Tool ids are checked the same way (see resolveBindableTools), so they resolve by default too.
        `when`(agentToolMapper.selectByIds(any())).thenAnswer { invocation ->
            invocation.getArgument<List<Long>>(0).map { id ->
                AgentTool().apply {
                    this.id = id
                    tenantId = 1L
                    name = "tool-$id"
                }
            }
        }
    }

    @Nested
    @DisplayName("Page Query Tests")
    inner class PageQueryTests {

        @Test
        @DisplayName("page - Normal pagination query")
        fun `page should return paginated results`() {
            // Given
            val agents = listOf(testAgent)
            `when`(agentMapper.selectAgentList(null, null, "admin", 1L)).thenReturn(agents)

            // When
            val page = agentService.page(null, null, 1, 10)

            // Then
            assertNotNull(page)
            assertTrue(page.total >= 0)
            verify(agentMapper).selectAgentList(null, null, "admin", 1L)
        }

        @Test
        @DisplayName("page - Filter by name")
        fun `page should filter by name`() {
            // Given
            val filteredAgents = listOf(testAgent)
            `when`(agentMapper.selectAgentList("Test", null, "admin", 1L)).thenReturn(filteredAgents)

            // When
            val page = agentService.page("Test", null, 1, 10)

            // Then
            assertNotNull(page)
            verify(agentMapper).selectAgentList("Test", null, "admin", 1L)
        }

        @Test
        @DisplayName("page - Filter by status")
        fun `page should filter by status`() {
            // Given
            val activeAgents = listOf(testAgent)
            `when`(agentMapper.selectAgentList(null, 1, "admin", 1L)).thenReturn(activeAgents)

            // When
            val page = agentService.page(null, 1, 1, 10)

            // Then
            assertNotNull(page)
            verify(agentMapper).selectAgentList(null, 1, "admin", 1L)
        }

        @Test
        @DisplayName("page - Push the current tenant into the query")
        fun `page should filter by tenant`() {
            // Given - 不带租户就等于把别的租户公开的智能体也列出来
            TenantContext.setTenantId(7L)
            try {
                `when`(agentMapper.selectAgentList(null, null, "admin", 7L)).thenReturn(listOf(testAgent))

                // When
                val page = agentService.page(null, null, 1, 10)

                // Then
                assertNotNull(page)
                verify(agentMapper).selectAgentList(null, null, "admin", 7L)
            } finally {
                TenantContext.clear()
            }
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
        @DisplayName("createAgent - Stamp the current tenant on the entity")
        fun `createAgent should stamp the current tenant`() {
            // Given - 服务层的取值；落库那一半见 AgentMapperTest 的 "insert should persist tenant id"
            TenantContext.setTenantId(7L)
            try {
                val request = AgentCreateRequest(
                    name = "Tenant Agent",
                    description = "Tenant agent description",
                    systemPrompt = "Tenant prompt",
                    modelId = 1L,
                    owner = "admin",
                )
                val captor = argumentCaptor<Agent>()
                `when`(agentMapper.insert(any())).thenReturn(1)

                // When
                assertTrue(agentService.createAgent(request))

                // Then
                verify(agentMapper).insert(captor.capture())
                assertEquals(7L, captor.firstValue.tenantId)
            } finally {
                TenantContext.clear()
            }
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
    @DisplayName("Skill 绑定约束")
    inner class SkillBindingConstraintTests {

        private fun skill(id: Long, name: String, repositoryId: Long) = Skill().apply {
            this.id = id
            this.name = name
            this.repositoryId = repositoryId
            status = 1
        }

        private fun stubAgent() {
            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(agentMapper.updateById(any())).thenReturn(1)
        }

        private fun updateWithSkills(skillList: String) = agentService.updateAgent(1L, AgentUpdateRequest(skillList = skillList))

        @Test
        @DisplayName("updateAgent - 内置仓库的技能不允许直接绑定")
        fun `updateAgent should reject a skill that lives in the builtin repository`() {
            // 内置仓库的技能由 agent 关联的 CLI 自动下发，再绑一次就会在 skillDetails 里出现两份
            stubAgent()
            `when`(skillRepositoryService.getBuiltinRepository()).thenReturn(
                SkillRepository().apply {
                    id = 9L
                    name = "builtin-cli-skills"
                },
            )
            `when`(skillMapper.selectByIds(listOf(1L, 2L))).thenReturn(
                listOf(skill(1L, "own-skill", 3L), skill(2L, "harnax-cli", 9L)),
            )

            val ex = assertThrows<BizException> { updateWithSkills("1,2") }

            assertTrue(ex.message!!.contains("builtin-cli-skills"))
            // 报错必须点名是哪个技能，否则运维面对一整面板的勾选无从下手
            assertTrue(ex.message!!.contains("harnax-cli"))
            verify(skillBindingMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("updateAgent - 同一 agent 绑定两个同名技能时拒绝")
        fun `updateAgent should reject two bound skills that share a name`() {
            // 技能名只在仓库内唯一（uk_skill_repo_active_name），跳仓库同名完全合法，而 harness 按
            // name 归并技能，两份都下发就会互相覆盖。写入时拦住比加载时默默丢一个可控得多
            stubAgent()
            `when`(skillRepositoryService.getBuiltinRepository()).thenReturn(null)
            `when`(skillMapper.selectByIds(listOf(1L, 2L))).thenReturn(
                listOf(skill(1L, "code-review", 3L), skill(2L, "code-review", 4L)),
            )

            val ex = assertThrows<BizException> { updateWithSkills("1,2") }

            assertTrue(ex.message!!.contains("code-review"))
            verify(skillBindingMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("updateAgent - 名称互不相同时照常写入绑定")
        fun `updateAgent should persist bindings when the skill names are distinct`() {
            stubAgent()
            `when`(skillRepositoryService.getBuiltinRepository()).thenReturn(
                SkillRepository().apply { id = 9L },
            )
            `when`(skillMapper.selectByIds(listOf(1L, 2L))).thenReturn(
                listOf(skill(1L, "code-review", 3L), skill(2L, "git-commit", 4L)),
            )

            assertTrue(updateWithSkills("1,2"))

            verify(skillBindingMapper).batchInsert(any())
        }

        @Test
        @DisplayName("updateAgent - 同名拦截不能因为找不到内置仓库而跳过")
        fun `updateAgent should still reject duplicated names when the builtin repository is missing`() {
            // 内置仓库缺失（尚未播种）只应该放宽「不能绑内置技能」这一条，同名拦截与它无关
            stubAgent()
            `when`(skillRepositoryService.getBuiltinRepository()).thenReturn(null)
            `when`(skillMapper.selectByIds(listOf(1L, 2L))).thenReturn(
                listOf(skill(1L, "same", 3L), skill(2L, "same", 4L)),
            )

            assertThrows<BizException> { updateWithSkills("1,2") }
        }

        @Test
        @DisplayName("updateAgent - 绑定的技能已被软删时不拦，仅写入传进来的 id")
        fun `updateAgent should tolerate ids the skill table no longer returns`() {
            // selectByIds 只返回 active = 1 的行，传了一个已删 id 时校验看到的行比请求少，
            // 不应因此报错；真正的绑定仍按原 id 写入，与改动前的行为一致
            stubAgent()
            `when`(skillRepositoryService.getBuiltinRepository()).thenReturn(null)
            `when`(skillMapper.selectByIds(listOf(1L, 2L))).thenReturn(listOf(skill(1L, "alive", 3L)))

            assertTrue(updateWithSkills("1,2"))

            verify(skillBindingMapper).batchInsert(any())
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

        @Test
        @DisplayName("getAgent - Return null for another tenant's agent")
        fun `getAgent should return null for another tenant agent`() {
            // Given - 列表按租户过滤，单行不按租户就等于把过滤做成摆设
            `when`(agentMapper.selectById(1L)).thenReturn(testAgent.apply { tenantId = 7L })

            // When & Then
            assertNull(agentService.getAgent(1L))
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
            // Verify binding table: old bindings deleted, no new ones inserted
            verify(mcpBindingMapper).deleteByAgentId(1L)
        }

        @Test
        @DisplayName("updateAgent - Collapse repeated MCP ids within one request")
        fun `updateAgent should dedupe repeated mcp ids`() {
            // Given - uk_agent_mcp_binding_agent_id_mcp_id rejects a duplicate batch outright
            val request = AgentUpdateRequest(
                mcpList = listOf(
                    AgentCreateRequest.McpConfig(id = 3L),
                    AgentCreateRequest.McpConfig(id = 3L),
                    AgentCreateRequest.McpConfig(id = 4L),
                ),
            )

            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(agentMapper.updateById(any())).thenReturn(1)

            // When
            agentService.updateAgent(1L, request)

            // Then - one row per mcpId, in first-seen order
            val captor = argumentCaptor<List<AgentMcpBinding>>()
            verify(mcpBindingMapper).batchInsert(captor.capture())
            assertEquals(listOf(3L, 4L), captor.firstValue.map { it.mcpId })
        }

        @Test
        @DisplayName("updateAgent - Reject an MCP id with no live server")
        fun `updateAgent should reject mcp id that does not resolve`() {
            // Given - 绑定一个查不到的 id，下发时会被 `?: continue` 静默丢掉
            val request = AgentUpdateRequest(
                mcpList = listOf(AgentCreateRequest.McpConfig(id = 9L)),
            )

            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(mcpServerMapper.selectByIds(listOf(9L))).thenReturn(emptyList())

            // When & Then
            val exception = assertThrows<BizException> { agentService.updateAgent(1L, request) }
            assertTrue(exception.message!!.contains("9"))
            verify(mcpBindingMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("updateAgent - Reject an MCP server of another tenant")
        fun `updateAgent should reject mcp server of another tenant`() {
            // Given - 跨租户 id 猜出来也绑不上，否则等于把别人的服务挂到自己 Agent 上
            val foreign = McpServer().apply {
                id = 9L
                tenantId = 2L
                name = "Other Tenant MCP"
                type = "streamablehttp"
            }
            val request = AgentUpdateRequest(
                mcpList = listOf(AgentCreateRequest.McpConfig(id = 9L)),
            )

            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(mcpServerMapper.selectByIds(listOf(9L))).thenReturn(listOf(foreign))

            // When & Then
            assertThrows<BizException> { agentService.updateAgent(1L, request) }
            verify(mcpBindingMapper, never()).batchInsert(any())
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
            // Verify binding table: old bindings deleted, no new ones inserted
            verify(skillBindingMapper).deleteByAgentId(1L)
        }

        @Test
        @DisplayName("updateAgent - Keep MCP bindings when the request omits them")
        fun `updateAgent should keep original mcp list when not provided`() {
            // Given
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
            // A null mcpList means "not sent", so the binding rows are left alone
            verify(mcpBindingMapper, never()).deleteByAgentId(any())
            verify(mcpBindingMapper, never()).batchInsert(any())
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
        @DisplayName("toggleAgentStatus - Idempotent: repeated calls with same status both succeed")
        fun `toggleAgentStatus should be idempotent for repeated calls with same status`() {
            // Given - agent is already disabled (status = 0)
            testAgent.status = 0
            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(agentMapper.updateStatus(1L, 0)).thenReturn(1)

            // When - call twice with the same target status
            val firstResult = agentService.toggleAgentStatus(1L, 0)
            val secondResult = agentService.toggleAgentStatus(1L, 0)

            // Then - both calls succeed, mapper update invoked each time (idempotent semantics)
            assertTrue(firstResult, "First call should succeed")
            assertTrue(secondResult, "Repeated call with same status should also succeed")
            verify(agentMapper, times(2)).selectById(1L)
            verify(agentMapper, times(2)).updateStatus(1L, 0)
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

        @Test
        @DisplayName("toggleAgentStatus - Throw when agent belongs to another tenant")
        fun `toggleAgentStatus should throw RuntimeException for another tenant agent`() {
            // Given - 写入口走 getAgent，否则单行读的租户守卫能被这个 writer 绕过
            `when`(agentMapper.selectById(1L)).thenReturn(testAgent.apply { tenantId = 7L })

            // When & Then
            val exception = assertThrows<RuntimeException> {
                agentService.toggleAgentStatus(1L, 0)
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
            // Verify cascade cleanup of all binding tables
            verify(toolBindingMapper).deleteByAgentId(1L)
            verify(mcpBindingMapper).deleteByAgentId(1L)
            verify(skillBindingMapper).deleteByAgentId(1L)
            verify(cliBindingMapper).deleteByAgentId(1L)
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

        @Test
        @DisplayName("deleteAgent - Reject another tenant's agent")
        fun `deleteAgent should reject another tenant agent`() {
            // Given - 删除与级联清绑定都只对本租户的行成立
            `when`(agentMapper.selectById(1L)).thenReturn(testAgent.apply { tenantId = 7L })

            // When & Then
            val exception = assertThrows<RuntimeException> { agentService.deleteAgent(1L) }
            assertEquals("Agent not found", exception.message)
            verify(agentMapper, never()).deleteById(any())
            verify(toolBindingMapper, never()).deleteByAgentId(any())
            verify(mcpBindingMapper, never()).deleteByAgentId(any())
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

            val mcpBinding = AgentMcpBinding().apply {
                agentId = 1L
                mcpId = 1L
                envBindings = null
            }

            val mcpServer = McpServer().apply {
                id = 1L
                name = "Weather MCP"
                description = "Weather service"
            }

            `when`(modelService.getModel(1L)).thenReturn(model)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)
            `when`(mcpBindingMapper.selectByAgentId(1L)).thenReturn(listOf(mcpBinding))
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
            verify(mcpBindingMapper).selectByAgentId(1L)
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
            }

            val sessions = listOf<Session>()
            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)

            // When
            val result = agentService.convertToResponse(agentWithoutMcp)

            // Then
            assertNotNull(result)
            // With no MCP binding rows, response.mcpList is never assigned and stays null
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
            }

            val sessions = listOf<Session>()
            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)

            // When
            val result = agentService.convertToResponse(agentWithoutSkill)

            // Then
            assertNotNull(result)
            // With no skill binding rows, response.skillList is never assigned and stays null
            assertNull(result.skillList)
        }

        @Test
        @DisplayName("convertToResponse - Handle invalid skill IDs gracefully")
        fun `convertToResponse should handle invalid skill ids gracefully`() {
            // Given - binding table has a skill binding but skill doesn't exist
            val agentWithOrphanBinding = Agent().apply {
                id = 1L
                name = "Orphan Skill Agent"
                modelId = 1L
            }

            val skillBinding = AgentSkillBinding().apply {
                agentId = 1L
                skillId = 999L
            }

            val sessions = listOf<Session>()
            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)
            `when`(skillBindingMapper.selectByAgentId(1L)).thenReturn(listOf(skillBinding))
            `when`(skillService.getSkill(999L)).thenReturn(null)

            // When
            val result = agentService.convertToResponse(agentWithOrphanBinding)

            // Then
            assertNotNull(result)
            // Skill binding exists but skill not found, so skillList is empty
            assertTrue(result.skillList?.isEmpty() == true)
        }

        @Test
        @DisplayName("convertToResponse - Skip non-existent MCP servers")
        fun `convertToResponse should skip non-existent mcp servers`() {
            // Given
            val mcpBinding = AgentMcpBinding().apply {
                agentId = 1L
                mcpId = 1L
            }

            val sessions = listOf<Session>()
            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)
            `when`(mcpBindingMapper.selectByAgentId(1L)).thenReturn(listOf(mcpBinding))
            `when`(mcpServerService.getMcpServer(1L)).thenReturn(null)

            // When
            val result = agentService.convertToResponse(testAgent)

            // Then
            assertNotNull(result)
            // MCP binding exists but server not found, so mcpList is empty
            assertTrue(result.mcpList?.isEmpty() == true)
        }

        @Test
        @DisplayName("convertToResponse - Skip non-existent skills")
        fun `convertToResponse should skip non-existent skills`() {
            // Given
            val skillBinding = AgentSkillBinding().apply {
                agentId = 1L
                skillId = 999L
            }

            val agentWithSkills = Agent().apply {
                id = 1L
                name = "Skill Agent"
                modelId = 1L
            }

            val sessions = listOf<Session>()
            `when`(modelService.getModel(1L)).thenReturn(null)
            `when`(sessionMapper.selectByAgentId(1L)).thenReturn(sessions)
            `when`(skillBindingMapper.selectByAgentId(1L)).thenReturn(listOf(skillBinding))
            `when`(skillService.getSkill(999L)).thenReturn(null)

            // When
            val result = agentService.convertToResponse(agentWithSkills)

            // Then
            assertNotNull(result)
            assertTrue(result.skillList?.isEmpty() == true)
        }
    }

    @Nested
    @DisplayName("环境参数绑定守卫")
    inner class EnvBindingGuardTests {

        private fun stubAgentForUpdate() {
            `when`(agentMapper.selectById(1L)).thenReturn(testAgent)
            `when`(agentMapper.updateById(any())).thenReturn(1)
        }

        private fun boundToolEnvJson(toolId: Long): String? {
            stubAgentForUpdate()
            agentService.updateAgent(
                1L,
                AgentUpdateRequest(
                    toolList = listOf(
                        ToolConfig(
                            id = toolId,
                            envBindings = listOf(EnvBinding(envKey = "API_KEY", envVarId = 7L, envValue = "******")),
                        ),
                    ),
                ),
            )
            val captor = argumentCaptor<List<AgentToolBinding>>()
            verify(toolBindingMapper).batchInsert(captor.capture())
            return captor.firstValue.single().envBindings
        }

        private fun requiredToolParam(vararg names: String): List<AgentToolEnvParam> = names.map { name ->
            AgentToolEnvParam().apply {
                toolId = 5L
                envParamName = name
                required = 1
            }
        }

        private fun liveEnvVariable(): EnvVariable = EnvVariable().apply {
            id = 7L
            tenantId = 1L
            envKey = "OPENAI_KEY"
            sensitive = 1
        }

        @Test
        @DisplayName("updateAgent - Reject a tool id with no live row")
        fun `updateAgent should reject tool id that does not resolve`() {
            // Given - 下发时这条绑定会被丢掉，operator 只看到少了一个工具
            val request = AgentUpdateRequest(toolList = listOf(ToolConfig(id = 9L)))
            stubAgentForUpdate()
            `when`(agentToolMapper.selectByIds(listOf(9L))).thenReturn(emptyList())

            // When & Then
            val exception = assertThrows<BizException> { agentService.updateAgent(1L, request) }
            assertTrue(exception.message!!.contains("9"), "message should carry the id: ${exception.message}")
            verify(toolBindingMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("updateAgent - Reject a tool of another tenant")
        fun `updateAgent should reject tool of another tenant`() {
            val foreign = AgentTool().apply {
                id = 9L
                tenantId = 2L
                name = "Other Tenant Tool"
            }
            val request = AgentUpdateRequest(toolList = listOf(ToolConfig(id = 9L)))
            stubAgentForUpdate()
            `when`(agentToolMapper.selectByIds(listOf(9L))).thenReturn(listOf(foreign))

            // When & Then
            val exception = assertThrows<BizException> { agentService.updateAgent(1L, request) }
            assertTrue(exception.message!!.contains("9"))
            verify(toolBindingMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("updateAgent - Reject an env var reference that resolves to nothing")
        fun `updateAgent should reject env var reference that does not resolve`() {
            // Given - 快照里只存指针，值靠运行时按 id 现取，所以 id 失效等于这个参数永久为空
            val request = AgentUpdateRequest(
                toolList = listOf(
                    ToolConfig(id = 5L, envBindings = listOf(EnvBinding(envKey = "API_KEY", envVarId = 7L))),
                ),
            )
            stubAgentForUpdate()
            `when`(envVariableService.getEnvVariable(7L)).thenReturn(null)

            // When & Then
            val exception = assertThrows<BizException> { agentService.updateAgent(1L, request) }
            assertTrue(exception.message!!.contains("7"), "message should carry the id: ${exception.message}")
            verify(toolBindingMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("updateAgent - Store a reference as a pointer, without the incoming mask")
        fun `updateAgent should not snapshot a value for a reference`() {
            // Given - 表单回填的是展示值（敏感变量即 `******`），落进快照就成了变量被删后的兜底值
            `when`(envVariableService.getEnvVariable(7L)).thenReturn(liveEnvVariable())

            // When
            val json = boundToolEnvJson(5L)

            // Then
            assertNotNull(json)
            assertTrue(json!!.contains("\"envVarId\":7"), "pointer kept: $json")
            assertFalse(json.contains("******"), "mask must not be persisted: $json")
            assertFalse(json.contains("envValue"), "no value stored for a reference: $json")
        }

        @Test
        @DisplayName("updateAgent - Reject a required tool param with nothing behind it")
        fun `updateAgent should reject unfilled required tool param`() {
            val request = AgentUpdateRequest(toolList = listOf(ToolConfig(id = 5L)))
            stubAgentForUpdate()
            `when`(agentToolEnvParamMapper.selectByToolId(5L)).thenReturn(requiredToolParam("API_KEY"))

            // When & Then
            val exception = assertThrows<BizException> { agentService.updateAgent(1L, request) }
            assertTrue(exception.message!!.contains("API_KEY"), "message should name the param: ${exception.message}")
            verify(toolBindingMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("updateAgent - A tool's own default does not answer a required param")
        fun `updateAgent should reject required tool param backed only by tool default`() {
            // Given - agent_tool.env_params 的默认值不进 ToolEnvContext，只有绑定值会下发
            val request = AgentUpdateRequest(toolList = listOf(ToolConfig(id = 5L)))
            stubAgentForUpdate()
            val params = requiredToolParam("API_KEY").also { it[0].defaultValue = "stored-default" }
            `when`(agentToolEnvParamMapper.selectByToolId(5L)).thenReturn(params)

            // When & Then
            assertThrows<BizException> { agentService.updateAgent(1L, request) }
        }

        @Test
        @DisplayName("updateAgent - Accept a tool param filled by a live reference")
        fun `updateAgent should accept required tool param filled by reference`() {
            val request = AgentUpdateRequest(
                toolList = listOf(
                    ToolConfig(id = 5L, envBindings = listOf(EnvBinding(envKey = "API_KEY", envVarId = 7L))),
                ),
            )
            stubAgentForUpdate()
            `when`(agentToolEnvParamMapper.selectByToolId(5L)).thenReturn(requiredToolParam("API_KEY"))
            `when`(envVariableService.getEnvVariable(7L)).thenReturn(liveEnvVariable())

            // When
            agentService.updateAgent(1L, request)

            // Then
            verify(toolBindingMapper).batchInsert(any())
        }

        @Test
        @DisplayName("updateAgent - A masked value typed back into the form is not a value")
        fun `updateAgent should treat a masked custom value as unfilled`() {
            val request = AgentUpdateRequest(
                toolList = listOf(
                    ToolConfig(id = 5L, envBindings = listOf(EnvBinding(envKey = "API_KEY", customValue = "sk****ef"))),
                ),
            )
            stubAgentForUpdate()
            `when`(agentToolEnvParamMapper.selectByToolId(5L)).thenReturn(requiredToolParam("API_KEY"))

            // When & Then
            assertThrows<BizException> { agentService.updateAgent(1L, request) }
        }

        @Test
        @DisplayName("updateAgent - An MCP's stored default does answer a required param")
        fun `updateAgent should accept required mcp param covered by server default`() {
            // Given - mcp_server.env_params 整份解密后作为 stdio 进程环境下发，默认值确实会到位
            val server = McpServer().apply {
                id = 3L
                tenantId = 1L
                name = "MCP 3"
                type = "stdio"
                envParams = "{}"
            }
            val request = AgentUpdateRequest(mcpList = listOf(AgentCreateRequest.McpConfig(id = 3L)))
            stubAgentForUpdate()
            `when`(mcpServerMapper.selectByIds(listOf(3L))).thenReturn(listOf(server))
            `when`(secretFieldEncryptor.deserializeToolEnvEntries("{}")).thenReturn(
                listOf(ToolEnvParamEntry(envParamName = "API_KEY", required = true, defaultValue = "stored-default")),
            )

            // When
            agentService.updateAgent(1L, request)

            // Then
            verify(mcpBindingMapper).batchInsert(any())
        }
    }
}
