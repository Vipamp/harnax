package com.agnetix.harnax.agent.service.runner

import com.agnetix.harnax.agent.service.client.AdminApiClient
import com.agnetix.harnax.agent.service.client.AgentSpecContextHolder
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.entity.dto.CliDetailDto
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
import tools.jackson.databind.ObjectMapper

class AgentSpecResolverTest {

    private lateinit var adminApiClient: AdminApiClient
    private lateinit var specContextHolder: AgentSpecContextHolder
    private lateinit var objectMapper: ObjectMapper
    private lateinit var resolver: AgentSpecResolver

    @BeforeEach
    fun setUp() {
        adminApiClient = mock(AdminApiClient::class.java)
        specContextHolder = AgentSpecContextHolder()
        objectMapper = ObjectMapper()
        resolver = AgentSpecResolver(adminApiClient, specContextHolder, objectMapper)
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
        cliDetails: List<CliDetailDto> = emptyList(),
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
        cliDetails = cliDetails,
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
    @DisplayName("Team session probe")
    inner class IsTeamSession {

        @Test
        fun `isTeamSession answers from admin without resolving an agent spec`() {
            `when`(adminApiClient.isTeamSession("web-123")).thenReturn(true)

            assertTrue(resolver.isTeamSession("web-123"))
            verify(adminApiClient, never()).getAgentSpec("web-123")
        }

        @Test
        fun `an ordinary session is not a team session`() {
            `when`(adminApiClient.isTeamSession("web-123")).thenReturn(false)

            assertFalse(resolver.isTeamSession("web-123"))
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
                    id = 10L,
                    name = "file-read",
                    displayName = "File Read",
                    displayNameZh = "文件读取",
                    description = "Read files",
                    beanName = "file-read",
                    methodName = "execute",
                    bindingNeedConfirm = true,
                ),
                ToolDetailDto(
                    id = 20L,
                    name = "shell-exec",
                    displayName = "Shell Exec",
                    displayNameZh = "执行命令",
                    description = "Run a command",
                    beanName = "shell-exec",
                    methodName = "execute",
                    bindingNeedConfirm = false,
                ),
            )
            val spec = buildSpecResponse(toolDetails = tools)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            assertEquals(2, agentSpec.toolSpecs.size)
            assertEquals(10L, agentSpec.toolSpecs[0].toolId)
            // ToolSpec.needConfirm 是 Boolean（来自 DTO 的 bindingNeedConfirm），不是实体里的 0/1
            assertTrue(agentSpec.toolSpecs[0].needConfirm)
            assertEquals(20L, agentSpec.toolSpecs[1].toolId)
            assertFalse(agentSpec.toolSpecs[1].needConfirm)
        }

        @Test
        fun `resolve should build MCP specs from mcpDetails`() {
            val mcps = listOf(
                McpDetailDto(id = 5L, name = "github-mcp", type = "sse"),
                McpDetailDto(id = 6L, name = "local-mcp", type = "stdio"),
            )
            val spec = buildSpecResponse(mcpDetails = mcps)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            assertEquals(2, agentSpec.mcpServices.size)
            assertEquals(5L, agentSpec.mcpServices[0].mcpId)
            assertEquals(6L, agentSpec.mcpServices[1].mcpId)
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
    @DisplayName("CLI 自带技能装载")
    inner class CliSkillInjection {

        private fun skill(id: Long, name: String) = SkillDetailDto(
            id = id,
            name = name,
            description = name,
            skillmd = "# $name",
            resources = "",
        )

        /** 一个由包登记的 CLI：技能随包下来，不再是运维在管理页勾出来的关联 */
        private fun cliWithSkill(id: Long, skillId: Long) = CliDetailDto(
            id = id,
            name = "cli-$id",
            packageObject = "cli-$id-1.0.0.harnaxcli.zip",
            packageDigest = "%064x".format(id),
            payloadDigest = "%064x".format(id + 100),
            skill = skill(skillId, "cli-$id"),
        )

        private fun cliWithoutSkill(id: Long) = cliWithSkill(id, 900L).copy(skill = null)

        @Test
        @DisplayName("agent 没选任何 CLI 时，一个 CLI 技能都不注入")
        fun `resolve should inject no CLI skill without a selected CLI`() {
            // CLI 技能在配置页既不展示也不可勾选，唯一的开关就是「有没有选中这个 CLI」
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(buildSpecResponse())

            val (agentSpec, _) = resolver.resolve("web-1")

            assertTrue(agentSpec.skills.isEmpty())
            assertTrue(specContextHolder.get()?.skillDetails?.isEmpty() ?: false)
        }

        @Test
        @DisplayName("选中一个 CLI 就装载它自带的技能，且排在 spec 自带技能之前")
        fun `resolve should inject the selected CLI skill ahead of the spec-defined ones`() {
            val spec = buildSpecResponse(
                skillDetails = listOf(skill(1L, "code-review")),
                cliDetails = listOf(cliWithSkill(7L, 90L)),
            )
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            assertEquals(listOf(90L, 1L), agentSpec.skills.map { it.skillId })
            // 上下文里存的也必须是合并后的结果，否则 SkillAdaptor 回退到 DB 又会把已删的行捞回来
            assertEquals(listOf(90L, 1L), specContextHolder.get()?.skillDetails?.map { it.id })
        }

        @Test
        @DisplayName("多个 CLI 的技能取并集")
        fun `resolve should union the skills of every selected CLI`() {
            val spec = buildSpecResponse(
                cliDetails = listOf(cliWithSkill(7L, 90L), cliWithSkill(8L, 91L)),
            )
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            assertEquals(setOf(90L, 91L), agentSpec.skills.map { it.skillId }.toSet())
        }

        /**
         * I5 的运行时那一半：admin 已经把 status=0 的 CLI 整个跳过了，这里能遇到的「没有技能」只有
         * 包登记的那一行被删掉。CLI 仍然要装进镜像，只是没人再指望模型知道怎么用它。
         */
        @Test
        @DisplayName("不带技能的 CLI 什么也不注入，但包坐标照常下发")
        fun `resolve should inject nothing for a CLI whose skill row is gone`() {
            val spec = buildSpecResponse(cliDetails = listOf(cliWithoutSkill(7L)))
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            assertTrue(agentSpec.skills.isEmpty())
            assertEquals(1, agentSpec.cliSpecs.size)
            assertEquals("%064x".format(7L), agentSpec.cliSpecs[0].packageDigest)
        }

        @Test
        @DisplayName("spec 已按 id 带过同一个技能时，CLI 那份不重复注入")
        fun `resolve should not inject a skill the spec already carries by id`() {
            val spec = buildSpecResponse(
                skillDetails = listOf(skill(1L, "code-review")),
                cliDetails = listOf(cliWithSkill(7L, 1L).copy(name = "code-review")),
            )
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            assertEquals(listOf(1L), agentSpec.skills.map { it.skillId })
        }

        @Test
        @DisplayName("CLI 技能与 spec 自带技能同名时不注入，保留运维显式绑定的那一个")
        fun `resolve should skip a CLI skill whose name collides with a spec-defined one`() {
            // 技能名只在仓库内唯一，租户自己的仓库完全可以放一个和 CLI 技能同名的技能。而 harness
            // 按 name 归并技能（AgentSkill.getSkillId() 是 name + "_" + source），两份都下发会让
            // SkillRegistry（后者替换前者）和 InMemorySkillRepository（取第一个）对「谁生效」判断相反
            val spec = buildSpecResponse(
                skillDetails = listOf(skill(1L, "cli-7")),
                cliDetails = listOf(cliWithSkill(7L, 90L), cliWithSkill(8L, 91L).copy(skill = skill(91L, "other"))),
            )
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            // 同名的 90 被让位，不同名的 91 照常注入且仍排在前面
            assertEquals(listOf(91L, 1L), agentSpec.skills.map { it.skillId })
        }

        @Test
        @DisplayName("包坐标与两类环境变量原样带到 CliSpec")
        fun `resolve should carry package coordinates and env through to CliSpec`() {
            val packageCli = cliWithSkill(7L, 90L).copy(
                version = "1.2.3",
                depsApt = listOf("ca-certificates"),
                checkCommand = "harnax --version",
                runtimeEnv = mapOf("HARNAX_URL" to "\${platform.adminUrl}"),
                envBindings = listOf(mapOf("envKey" to "HARNAX_TOKEN", "envValue" to "agent-token")),
            )
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(
                buildSpecResponse(cliDetails = listOf(packageCli)),
            )

            val (agentSpec, _) = resolver.resolve("web-1")

            val cli = agentSpec.cliSpecs.single()
            assertEquals(7L, cli.cliId)
            assertEquals("1.2.3", cli.version)
            assertEquals(packageCli.packageObject, cli.packageObject)
            assertEquals(packageCli.payloadDigest, cli.payloadDigest)
            assertEquals(listOf("ca-certificates"), cli.depsApt)
            assertEquals("harnax --version", cli.checkCommand)
            // 槽位留给 launcher 填：只有它知道这次部署的 admin URL 与内部密钥
            assertEquals(mapOf("HARNAX_URL" to "\${platform.adminUrl}"), cli.runtimeEnv)
            assertEquals(mapOf("HARNAX_TOKEN" to "agent-token"), cli.envBindings)
        }
    }

    @Nested
    @DisplayName("Env bindings parsing")
    inner class EnvBindingsParsing {

        @Test
        fun `resolve should parse env bindings from legacy toolList JSON`() {
            val toolListJson = """[{"id":1,"need_confirm":false,"env_bindings":[{"envKey":"API_KEY","envValue":"sk-123"}]}]"""
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
            val mcpListJson = """[{"id":1,"env_bindings":[{"envKey":"MCP_TOKEN","envValue":"token-abc"}]}]"""
            val spec = buildSpecResponse(mcpList = mcpListJson)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            val envContext = agentSpec.contextForTools.filterIsInstance<ToolEnvContext>().firstOrNull()
            assertNotNull(envContext)
            assertEquals("token-abc", envContext!!.bindings["MCP_TOKEN"])
        }

        @Test
        fun `resolve should merge env bindings from both toolList and mcpList`() {
            val toolListJson = """[{"id":1,"need_confirm":false,"env_bindings":[{"envKey":"TOOL_KEY","envValue":"tool-val"}]}]"""
            val mcpListJson = """[{"id":2,"env_bindings":[{"envKey":"MCP_KEY","envValue":"mcp-val"}]}]"""
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
            val toolListJson = """[{"id":1,"need_confirm":false,"env_bindings":[]}]"""
            val spec = buildSpecResponse(toolList = toolListJson)
            `when`(adminApiClient.getAgentSpec("web-1")).thenReturn(spec)

            val (agentSpec, _) = resolver.resolve("web-1")

            val envContext = agentSpec.contextForTools.filterIsInstance<ToolEnvContext>().firstOrNull()
            // Empty env_bindings still yields an (empty) container so tool injection never fails
            assertNotNull(envContext)
            assertTrue(envContext!!.bindings.isEmpty())
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
