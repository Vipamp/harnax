package com.agnetix.harnax.agent.service.runner

import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.McpDetailDto
import com.agnetix.harnax.entity.dto.ModelConfigDto
import com.agnetix.harnax.entity.dto.SkillDetailDto
import com.agnetix.harnax.entity.dto.ToolDetailDto
import com.agnetix.harnax.tools.sdk.ToolEnvContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.*
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper

class AgentSpecResolverTest {

    private lateinit var adminApiClient: AdminApiClient
    private lateinit var specContextHolder: AgentSpecContextHolder
    private lateinit var objectMapper: ObjectMapper
    private lateinit var builtinSkillRegistry: BuiltinSkillRegistry
    private lateinit var resolver: AgentSpecResolver

    @BeforeEach
    fun setUp() {
        adminApiClient = mock(AdminApiClient::class.java)
        specContextHolder = AgentSpecContextHolder()
        objectMapper = ObjectMapper()
        builtinSkillRegistry = mock(BuiltinSkillRegistry::class.java)
        whenever(builtinSkillRegistry.getSkills()).thenReturn(emptyList())
        resolver = AgentSpecResolver(adminApiClient, specContextHolder, objectMapper, builtinSkillRegistry)
    }

    private fun buildSpecResponse(
        enableThink: Int = 0,
        enableSearch: Int = 0,
        enablePlan: Int = 0,
        modelSupportInternet: Int = 0,
        modelSupportReasoning: Int = 0,
        permissionMode: String = "DEFAULT",
        toolList: String = "[]",
        mcpList: String = "[]",
        toolDetails: List<ToolDetailDto> = emptyList(),
        mcpDetails: List<McpDetailDto> = emptyList(),
        skillDetails: List<SkillDetailDto> = emptyList(),
        modelConfig: ModelConfigDto? = null,
    ) = AgentSpecInfoResponse(
        agentId = 1L,
        agentName = "TestAgent",
        description = "A test agent",
        systemPrompt = "You are a test assistant",
        modelId = 100L,
        toolList = toolList,
        mcpList = mcpList,
        enableThink = enableThink,
        enableSearch = enableSearch,
        enablePlan = enablePlan,
        permissionMode = permissionMode,
        modelSupportInternet = modelSupportInternet,
        modelSupportReasoning = modelSupportReasoning,
        modelConfig = modelConfig,
        toolDetails = toolDetails,
        mcpDetails = mcpDetails,
        skillDetails = skillDetails,
    )

    @Nested
    @DisplayName("Resolve basic flow")
    inner class ResolveBasicFlow {

        @Test
        fun `resolve should call adminApiClient and return specs`() {
            val specResponse = buildSpecResponse()
            `when`(adminApiClient.getAgentSpec("web-123")).thenReturn(specResponse)

            val (agentSpec, chatSpec) = resolver.resolve("web-123")

            assertEquals(1L, agentSpec.id)
            assertEquals("TestAgent", agentSpec.name)
            assertEquals("You are a test assistant", agentSpec.systemPrompt)
            assertEquals(100L, agentSpec.chatModelId)
            verify(adminApiClient).getAgentSpec("web-123")
        }

        @Test
        fun `resolve should set spec in context holder`() {
            val specResponse = buildSpecResponse()
            `when`(adminApiClient.getAgentSpec("web-123")).thenReturn(specResponse)

            resolver.resolve("web-123")

            val context = specContextHolder.get()
            assertNotNull(context)
            assertEquals(1L, context!!.agentId)
            assertEquals("TestAgent", context.agentName)
        }

        @Test
        fun `resolve should propagate exception from adminApiClient`() {
            `when`(adminApiClient.getAgentSpec("web-999"))
                .thenThrow(RuntimeException("Connection refused"))

            assertThrows<RuntimeException> {
                resolver.resolve("web-999")
            }
        }
    }

    @Nested
    @DisplayName("ChatSpec capability masking")
    inner class ChatSpecMasking {

        @Test
        fun `enableSearch should be true when session and model both support it`() {
            val spec = buildSpecResponse(enableSearch = 1, modelSupportInternet = 1)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (_, chatSpec) = resolver.resolve("web-1")

            assertTrue(chatSpec.enableSearch)
        }

        @Test
        fun `enableSearch should be false when model does not support internet`() {
            val spec = buildSpecResponse(enableSearch = 1, modelSupportInternet = 0)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (_, chatSpec) = resolver.resolve("web-1")

            assertFalse(chatSpec.enableSearch)
        }

        @Test
        fun `enableThinking should be true when session and model both support it`() {
            val spec = buildSpecResponse(enableThink = 1, modelSupportReasoning = 1)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (_, chatSpec) = resolver.resolve("web-1")

            assertTrue(chatSpec.enableThinking)
        }

        @Test
        fun `enableThinking should be false when model does not support reasoning`() {
            val spec = buildSpecResponse(enableThink = 1, modelSupportReasoning = 0)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (_, chatSpec) = resolver.resolve("web-1")

            assertFalse(chatSpec.enableThinking)
        }

        @Test
        fun `enablePlan should follow session flag regardless of model`() {
            val spec = buildSpecResponse(enablePlan = 1)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (_, chatSpec) = resolver.resolve("web-1")

            assertTrue(chatSpec.enablePlan)
        }

        @Test
        fun `permissionMode should be passed through to chatSpec`() {
            val spec = buildSpecResponse(permissionMode = "BYPASS")
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (_, chatSpec) = resolver.resolve("web-1")

            assertEquals("BYPASS", chatSpec.permissionMode)
        }
    }

    @Nested
    @DisplayName("Tool/MCP/Skill detail building")
    inner class DetailBuilding {

        @Test
        fun `resolve should build tool specs from toolDetails`() {
            val tools = listOf(
                ToolDetailDto(
                    id = 10L, name = "file-read", displayName = "File Read",
                    displayNameZh = "文件读取", description = "Read files",
                    type = "BUILTIN", beanName = "file-read", methodName = "execute",
                    enableSkip = "true", bindingNeedConfirm = true,
                ),
                ToolDetailDto(
                    id = 20L, name = "http-call", displayName = "HTTP Call",
                    displayNameZh = "HTTP调用", description = "Make HTTP calls",
                    type = "BUILTIN", beanName = "http-call", methodName = "execute",
                    enableSkip = "false", bindingNeedConfirm = false,
                ),
            )
            val spec = buildSpecResponse(toolDetails = tools)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            assertEquals(2, agentSpec.toolSpecs.size)
            assertEquals(10L, agentSpec.toolSpecs[0].toolId)
            assertTrue(agentSpec.toolSpecs[0].skipIfMissing)
            // ToolSpec.needConfirm 是 Boolean（来自 DTO 的 bindingNeedConfirm），不是实体里的 0/1
            assertTrue(agentSpec.toolSpecs[0].needConfirm)
            assertEquals(20L, agentSpec.toolSpecs[1].toolId)
            assertFalse(agentSpec.toolSpecs[1].skipIfMissing)
            assertFalse(agentSpec.toolSpecs[1].needConfirm)
        }

        @Test
        fun `resolve should build MCP specs from mcpDetails`() {
            val mcps = listOf(
                McpDetailDto(id = 5L, name = "github-mcp", type = "sse", enableSkip = "true"),
                McpDetailDto(id = 6L, name = "local-mcp", type = "stdio", enableSkip = "false"),
            )
            val spec = buildSpecResponse(mcpDetails = mcps)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            assertEquals(2, agentSpec.mcpServices.size)
            assertEquals(5L, agentSpec.mcpServices[0].mcpId)
            assertTrue(agentSpec.mcpServices[0].skipIfMissing)
            assertEquals(6L, agentSpec.mcpServices[1].mcpId)
            assertFalse(agentSpec.mcpServices[1].skipIfMissing)
        }

        @Test
        fun `resolve should build skill specs from skillDetails`() {
            val skills = listOf(
                SkillDetailDto(
                    id = 1L,
                    name = "code-review",
                    description = "Review code",
                    skillmd = "# Code Review",
                    resources = "",
                ),
                SkillDetailDto(
                    id = 2L,
                    name = "git-commit",
                    description = "Commit changes",
                    skillmd = "# Git Commit",
                    resources = "",
                ),
            )
            val spec = buildSpecResponse(skillDetails = skills)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            assertEquals(2, agentSpec.skills.size)
            assertEquals(1L, agentSpec.skills[0].skillId)
            assertEquals("code-review", agentSpec.skills[0].skillName)
            assertEquals(2L, agentSpec.skills[1].skillId)
        }
    }

    @Nested
    @DisplayName("内置技能注入")
    inner class BuiltinSkillInjection {

        private fun skill(id: Long, name: String) = SkillDetailDto(
            id = id,
            name = name,
            description = name,
            skillmd = "# $name",
            resources = "",
        )

        @Test
        @DisplayName("内置技能排在 spec 自带技能之前注入")
        fun `resolve should inject built-in skills ahead of the spec-defined ones`() {
            whenever(builtinSkillRegistry.getSkills()).thenReturn(listOf(skill(90L, "harnax-cli")))
            val spec = buildSpecResponse(skillDetails = listOf(skill(1L, "code-review")))
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            assertEquals(listOf(90L, 1L), agentSpec.skills.map { it.skillId })
        }

        @Test
        @DisplayName("spec 已按 id 带过同一个技能时，内置那份不重复注入")
        fun `resolve should not inject a built-in skill the spec already carries by id`() {
            whenever(builtinSkillRegistry.getSkills()).thenReturn(listOf(skill(1L, "code-review")))
            val spec = buildSpecResponse(skillDetails = listOf(skill(1L, "code-review")))
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            assertEquals(listOf(1L), agentSpec.skills.map { it.skillId })
        }

        @Test
        @DisplayName("内置技能与 spec 自带技能同名时不注入，保留运维显式绑定的那一个")
        fun `resolve should skip a built-in skill whose name collides with a spec-defined one`() {
            // 技能名只在仓库内唯一，租户自己的仓库完全可以放一个和内置技能同名的技能。而 harness
            // 按 name 归并技能（AgentSkill.getSkillId() 是 name + "_" + source），两份都下发会让
            // SkillRegistry（后者替换前者）和 InMemorySkillRepository（取第一个）对「谁生效」判断相反
            whenever(builtinSkillRegistry.getSkills()).thenReturn(
                listOf(skill(90L, "code-review"), skill(91L, "harnax-cli")),
            )
            val spec = buildSpecResponse(skillDetails = listOf(skill(1L, "code-review")))
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            // 同名的 90 被让位，不同名的 91 照常注入且仍排在前面
            assertEquals(listOf(91L, 1L), agentSpec.skills.map { it.skillId })
            // 上下文里存的也必须是合并后的结果，否则 SkillAdaptor 回退到 DB 又会把 90 捞回来
            assertEquals(listOf(91L, 1L), specContextHolder.get()?.skillDetails?.map { it.id })
        }
    }

    @Nested
    @DisplayName("Env bindings parsing")
    inner class EnvBindingsParsing {

        @Test
        fun `resolve should parse env bindings from legacy toolList JSON`() {
            val toolListJson = """[{"id":1,"enable_skip":false,"need_confirm":false,"env_bindings":[{"envKey":"API_KEY","envValue":"sk-123"}]}]"""
            val spec = buildSpecResponse(toolList = toolListJson)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            // The agentSpec should have ToolEnvContext registered
            val envContext = agentSpec.contextForTools.filterIsInstance<ToolEnvContext>().firstOrNull()
            assertNotNull(envContext)
            assertEquals("sk-123", envContext!!.bindings["API_KEY"])
        }

        @Test
        fun `resolve should parse env bindings from legacy mcpList JSON`() {
            val mcpListJson = """[{"id":1,"enable_skip":false,"need_confirm":false,"env_bindings":[{"envKey":"MCP_TOKEN","envValue":"token-abc"}]}]"""
            val spec = buildSpecResponse(mcpList = mcpListJson)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            val envContext = agentSpec.contextForTools.filterIsInstance<ToolEnvContext>().firstOrNull()
            assertNotNull(envContext)
            assertEquals("token-abc", envContext!!.bindings["MCP_TOKEN"])
        }

        @Test
        fun `resolve should merge env bindings from both toolList and mcpList`() {
            val toolListJson = """[{"id":1,"enable_skip":false,"need_confirm":false,"env_bindings":[{"envKey":"TOOL_KEY","envValue":"tool-val"}]}]"""
            val mcpListJson = """[{"id":2,"enable_skip":false,"need_confirm":false,"env_bindings":[{"envKey":"MCP_KEY","envValue":"mcp-val"}]}]"""
            val spec = buildSpecResponse(toolList = toolListJson, mcpList = mcpListJson)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            val envContext = agentSpec.contextForTools.filterIsInstance<ToolEnvContext>().firstOrNull()
            assertNotNull(envContext)
            assertEquals("tool-val", envContext!!.bindings["TOOL_KEY"])
            assertEquals("mcp-val", envContext.bindings["MCP_KEY"])
        }

        @Test
        fun `resolve should handle empty env bindings gracefully`() {
            val toolListJson = """[{"id":1,"enable_skip":false,"need_confirm":false,"env_bindings":[]}]"""
            val spec = buildSpecResponse(toolList = toolListJson)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            val envContext = agentSpec.contextForTools.filterIsInstance<ToolEnvContext>().firstOrNull()
            // Empty env_bindings from tools + empty from MCPs = no ToolEnvContext registered
            assertNull(envContext)
        }

        @Test
        fun `resolve should handle malformed legacy JSON gracefully`() {
            val spec = buildSpecResponse(toolList = "not-valid-json{{{")
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            // Should not throw
            val (agentSpec, _) = resolver.resolve("web-1")

            assertNotNull(agentSpec)
        }
    }
}
