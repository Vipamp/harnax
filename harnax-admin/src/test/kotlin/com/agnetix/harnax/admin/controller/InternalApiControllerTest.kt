package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.service.EnvVariableService
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentCliBinding
import com.agnetix.harnax.entity.AgentMcpBinding
import com.agnetix.harnax.entity.AgentSkillBinding
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.entity.AgentToolBinding
import com.agnetix.harnax.entity.ApiKeyEntity
import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.entity.Cli
import com.agnetix.harnax.entity.CliSkillBinding
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.mapper.AgentCliBindingMapper
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.mapper.AgentToolBindingMapper
import com.agnetix.harnax.mapper.AgentToolMapper
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.CliMapper
import com.agnetix.harnax.mapper.CliSkillBindingMapper
import com.agnetix.harnax.mapper.McpServerMapper
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.ModelProviderMapper
import com.agnetix.harnax.mapper.SessionMapper
import com.agnetix.harnax.mapper.SkillMapper
import com.agnetix.harnax.mapper.SkillRepositoryMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness

/**
 * InternalApiController 单元测试
 * 测试 API Key 验证和系统 Key 获取接口
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InternalApiControllerTest {

    @Mock
    private lateinit var apiKeyMapper: ApiKeyMapper

    @Mock
    private lateinit var sessionMapper: SessionMapper

    @Mock
    private lateinit var modelMapper: ModelMapper

    @Mock
    private lateinit var aesUtil: AesUtil

    @Mock
    private lateinit var secretFieldEncryptor: SecretFieldEncryptor

    @Mock
    private lateinit var agentTaskMapper: AgentTaskMapper

    @Mock
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var channelMapper: ChannelMapper

    @Mock
    private lateinit var toolBindingMapper: AgentToolBindingMapper

    @Mock
    private lateinit var mcpBindingMapper: AgentMcpBindingMapper

    @Mock
    private lateinit var skillBindingMapper: AgentSkillBindingMapper

    @Mock
    private lateinit var skillMapper: SkillMapper

    // 下面几个 mapper 在本类的用例里并不被 stub，但必须声明：控制器是构造注入，而 Kotlin
    // 的非空参数会在构造时校验实参，Mockito 对没声明的构造参数传的是 null，结果是整个类
    // 以 InjectMocksException 全红（不是只红用到它的那一个用例）。控制器构造函数加参时
    // 这里要同步补上。
    @Mock
    private lateinit var modelProviderMapper: ModelProviderMapper

    @Mock
    private lateinit var agentToolMapper: AgentToolMapper

    @Mock
    private lateinit var mcpServerMapper: McpServerMapper

    @Mock
    private lateinit var skillRepositoryMapper: SkillRepositoryMapper

    @Mock
    private lateinit var cliBindingMapper: AgentCliBindingMapper

    @Mock
    private lateinit var cliMapper: CliMapper

    @Mock
    private lateinit var cliSkillBindingMapper: CliSkillBindingMapper

    @Mock
    private lateinit var envVariableService: EnvVariableService

    @InjectMocks
    private lateinit var controller: InternalApiController

    @Nested
    @DisplayName("API Key 验证接口")
    inner class ValidateApiKeyTests {

        @Test
        @DisplayName("validateApiKey - key存在时返回验证信息")
        fun `validateApiKey should return key info when found`() {
            // Given
            val entity = ApiKeyEntity().apply {
                id = 1L
                name = "test_key"
                keyHash = "abc123hash"
                keyPrefix = "hnx_sk_live_...abcd"
                scopes = "chat"
                tenantId = 10L
                rateLimit = 300
                enabled = 1
                expiresAt = null
                keyType = "PERMANENT"
                userId = 77L
            }
            `when`(apiKeyMapper.selectByKeyHash("abc123hash")).thenReturn(entity)

            // When
            val result = controller.validateApiKey(
                InternalApiController.ApiKeyValidateRequest(keyHash = "abc123hash"),
            )

            // Then
            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals("test_key", result.data?.name)
            assertEquals(77L, result.data?.userId)
            assertEquals("chat", result.data?.scopes)
            assertEquals(10L, result.data?.tenantId)
            assertEquals(300, result.data?.rateLimit)
            assertTrue(result.data?.enabled == true)
        }

        @Test
        @DisplayName("validateApiKey - key不存在时返回null")
        fun `validateApiKey should return null when key not found`() {
            // Given
            `when`(apiKeyMapper.selectByKeyHash("nonexistent_hash")).thenReturn(null)

            // When
            val result = controller.validateApiKey(
                InternalApiController.ApiKeyValidateRequest(keyHash = "nonexistent_hash"),
            )

            // Then
            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("validateApiKey - 返回禁用的key信息")
        fun `validateApiKey should return disabled key info`() {
            // Given
            val entity = ApiKeyEntity().apply {
                id = 2L
                name = "disabled_key"
                keyHash = "def456hash"
                scopes = "chat"
                rateLimit = 60
                enabled = 0
                keyType = "TEMPORARY"
            }
            `when`(apiKeyMapper.selectByKeyHash("def456hash")).thenReturn(entity)

            // When
            val result = controller.validateApiKey(
                InternalApiController.ApiKeyValidateRequest(keyHash = "def456hash"),
            )

            // Then
            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertFalse(result.data?.enabled == true)
        }

        @Test
        @DisplayName("validateApiKey - SYSTEM key 无归属人时 userId 为空")
        fun `validateApiKey should leave userId null for a system key`() {
            // Given - 系统密钥后面没有人，运行侧不能拿 0 当 userId 去解析按人的上游授权
            val entity = ApiKeyEntity().apply {
                id = 3L
                name = "system_key"
                keyHash = "sys123hash"
                scopes = "chat"
                rateLimit = 60
                enabled = 1
                keyType = "SYSTEM"
                userId = null
            }
            `when`(apiKeyMapper.selectByKeyHash("sys123hash")).thenReturn(entity)

            // When
            val result = controller.validateApiKey(
                InternalApiController.ApiKeyValidateRequest(keyHash = "sys123hash"),
            )

            // Then
            assertTrue(result.isSuccess())
            assertNull(result.data?.userId)
        }
    }

    @Nested
    @DisplayName("系统Key获取接口")
    inner class GetSystemKeyTests {

        @Test
        @DisplayName("getSystemKey - 系统Key存在时解密并返回")
        fun `getSystemKey should return decrypted key when found`() {
            // Given
            val entity = ApiKeyEntity().apply {
                id = 3L
                name = "system_channel-service"
                keyType = "SYSTEM"
                serviceName = "channel-service"
                rawKeyEncrypted = "encrypted_system_key_base64"
                keyHash = "systemhash"
                keyPrefix = "hnx_sk_live_...ijkl"
                scopes = "chat"
                rateLimit = 600
                enabled = 1
            }
            `when`(apiKeyMapper.selectSystemKeyByServiceName("channel-service")).thenReturn(entity)
            `when`(aesUtil.decrypt("encrypted_system_key_base64")).thenReturn("hnx_sk_live_decrypted_system_key")

            // When
            val result = controller.getSystemKey(
                InternalApiController.SystemKeyRequest(serviceName = "channel-service"),
            )

            // Then
            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals("hnx_sk_live_decrypted_system_key", result.data?.rawKey)
            assertEquals("hnx_sk_live_...ijkl", result.data?.keyPrefix)
        }

        @Test
        @DisplayName("getSystemKey - 系统Key不存在时返回null")
        fun `getSystemKey should return null when key not found`() {
            // Given
            `when`(apiKeyMapper.selectSystemKeyByServiceName("unknown-service")).thenReturn(null)

            // When
            val result = controller.getSystemKey(
                InternalApiController.SystemKeyRequest(serviceName = "unknown-service"),
            )

            // Then
            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getSystemKey - 解密失败时抛出异常")
        fun `getSystemKey should throw when decryption fails`() {
            // Given
            val entity = ApiKeyEntity().apply {
                id = 3L
                name = "system_channel-service"
                keyType = "SYSTEM"
                serviceName = "channel-service"
                rawKeyEncrypted = "corrupted_encrypted_data"
                keyHash = "systemhash"
                keyPrefix = "hnx_sk_live_...ijkl"
            }
            `when`(apiKeyMapper.selectSystemKeyByServiceName("channel-service")).thenReturn(entity)
            `when`(aesUtil.decrypt("corrupted_encrypted_data")).thenThrow(RuntimeException("AES decryption failed"))

            // When & Then
            org.junit.jupiter.api.assertThrows<RuntimeException> {
                controller.getSystemKey(
                    InternalApiController.SystemKeyRequest(serviceName = "channel-service"),
                )
            }
        }
    }

    @Nested
    @DisplayName("Agent Task Spec 接口")
    inner class GetAgentTaskSpecTests {

        @Test
        @DisplayName("getAgentTaskSpec - 任务存在时返回 AgentSpec")
        fun `getAgentTaskSpec should return spec when task and agent exist`() {
            val task = AgentTask().apply {
                id = 1L
                agentId = 100L
                name = "Daily News"
            }
            val agent = Agent().apply {
                id = 100L
                name = "News Agent"
                description = "News agent desc"
                systemPrompt = "You are a news agent"
                modelId = 5L
            }
            `when`(agentTaskMapper.selectAnyById(1L)).thenReturn(task)
            `when`(agentMapper.selectById(100L)).thenReturn(agent)

            val result = controller.getAgentTaskSpec(1L)

            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals(100L, result.data?.agentId)
            assertEquals("News Agent", result.data?.agentName)
            assertEquals("You are a news agent", result.data?.systemPrompt)
            assertEquals(5L, result.data?.modelId)
        }

        @Test
        @DisplayName("getAgentTaskSpec - 能力清单取自绑定表")
        fun `getAgentTaskSpec should read capability lists from binding tables`() {
            // Given - agent 上的 mcp_list / skill_list 历史列已删除，定时任务侧只能看绑定表
            val task = AgentTask().apply {
                id = 1L
                agentId = 100L
            }
            val agent = Agent().apply {
                id = 100L
                name = "News Agent"
            }
            `when`(agentTaskMapper.selectAnyById(1L)).thenReturn(task)
            `when`(agentMapper.selectById(100L)).thenReturn(agent)
            `when`(mcpBindingMapper.selectByAgentId(100L)).thenReturn(
                listOf(
                    AgentMcpBinding().apply {
                        agentId = 100L
                        mcpId = 7L
                        envBindings = """[{"envKey":"MCP_TOKEN","customValue":"tok"}]"""
                    },
                ),
            )
            `when`(skillBindingMapper.selectByAgentId(100L)).thenReturn(
                listOf(
                    AgentSkillBinding().apply {
                        agentId = 100L
                        skillId = 21L
                    },
                    AgentSkillBinding().apply {
                        agentId = 100L
                        skillId = 22L
                    },
                ),
            )

            val data = controller.getAgentTaskSpec(1L).data

            assertEquals("""[{"id":7,"env_bindings":[{"envKey":"MCP_TOKEN","envValue":"tok"}]}]""", data?.mcpList)
            assertEquals("21,22", data?.skillList)
        }

        @Test
        @DisplayName("getAgentTaskSpec - 任务不存在时返回错误")
        fun `getAgentTaskSpec should return error when task not found`() {
            `when`(agentTaskMapper.selectAnyById(999L)).thenReturn(null)

            val result = controller.getAgentTaskSpec(999L)

            assertFalse(result.isSuccess())
            assertTrue(result.message.contains("Agent task not found"))
        }

        @Test
        @DisplayName("getAgentTaskSpec - Agent不存在时返回错误")
        fun `getAgentTaskSpec should return error when agent not found`() {
            val task = AgentTask().apply {
                id = 1L
                agentId = 999L
            }
            `when`(agentTaskMapper.selectAnyById(1L)).thenReturn(task)
            `when`(agentMapper.selectById(999L)).thenReturn(null)

            val result = controller.getAgentTaskSpec(1L)

            assertFalse(result.isSuccess())
            assertTrue(result.message.contains("Agent not found"))
        }
    }

    @Nested
    @DisplayName("统一 Agent Spec 接口")
    inner class GetAgentSpecTests {

        private fun stubAgent() = Agent().apply {
            id = 100L
            name = "Test Agent"
            description = "Test agent desc"
            systemPrompt = "You are a test agent"
            modelId = 5L
        }

        @Test
        @DisplayName("getAgentSpec - web session 返回 AgentSpec")
        fun `getAgentSpec should resolve from session for web prefix`() {
            val session = Session().apply {
                sessionId = "web-abc123"
                agentId = 100L
                enableThink = 1
                enableSearch = 0
                enablePlan = 0
            }
            `when`(sessionMapper.selectBySessionIdAndStatus("web-abc123", 1)).thenReturn(session)
            `when`(agentMapper.selectById(100L)).thenReturn(stubAgent())

            val result = controller.getAgentSpec("web-abc123")

            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals(100L, result.data?.agentId)
            assertEquals("Test Agent", result.data?.agentName)
            assertEquals(1, result.data?.enableThink)
            assertEquals(0, result.data?.enableSearch)
        }

        @Test
        @DisplayName("getAgentSpec - chn session 返回 AgentSpec")
        fun `getAgentSpec should resolve from channel for chn prefix`() {
            val channel = Channel().apply {
                id = 1L
                agentId = 100L
                sessionId = "chn-xyz"
            }
            `when`(channelMapper.selectBySessionId("chn-xyz")).thenReturn(channel)
            `when`(agentMapper.selectById(100L)).thenReturn(stubAgent())

            val result = controller.getAgentSpec("chn-xyz")

            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals(100L, result.data?.agentId)
        }

        @Test
        @DisplayName("getAgentSpec - task session 返回 AgentSpec")
        fun `getAgentSpec should resolve from task for task prefix`() {
            val task = AgentTask().apply {
                id = 42L
                agentId = 100L
            }
            `when`(agentTaskMapper.selectAnyById(42L)).thenReturn(task)
            `when`(agentMapper.selectById(100L)).thenReturn(stubAgent())

            val result = controller.getAgentSpec("task-42-uuid123")

            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals(100L, result.data?.agentId)
        }

        @Test
        @DisplayName("getAgentSpec - 未知前缀返回错误")
        fun `getAgentSpec should return error for unknown prefix`() {
            val result = controller.getAgentSpec("unknown-123")

            assertFalse(result.isSuccess())
            assertTrue(result.message.contains("Unknown sessionId prefix"))
        }

        @Test
        @DisplayName("getAgentSpec - session不存在时返回错误")
        fun `getAgentSpec should return error when session not found`() {
            `when`(sessionMapper.selectBySessionIdAndStatus("web-notfound", 1)).thenReturn(null)

            val result = controller.getAgentSpec("web-notfound")

            assertFalse(result.isSuccess())
            assertTrue(result.message.contains("Session not found"))
        }
    }

    /**
     * 敏感配置的下发口径：agent-service 不持有 AES 密钥，所以 `mcp_server.headers`、
     * `mcp_server.envParams`、`agent_tool.http_headers` 必须在下发前就解密成扁平明文对象。
     * 早先这些字段是加密态原文直接给出，运行时解密器为 null，最终 headers 被静默置空。
     */
    @Nested
    @DisplayName("敏感配置下发前解密")
    inner class SecretConfigDeliveryTests {

        private fun stubWebSession() {
            val session = Session().apply {
                sessionId = "web-secret"
                agentId = 100L
                enableThink = 0
                enableSearch = 0
                enablePlan = 0
            }
            `when`(sessionMapper.selectBySessionIdAndStatus("web-secret", 1)).thenReturn(session)
            `when`(agentMapper.selectById(100L)).thenReturn(
                Agent().apply {
                    id = 100L
                    name = "Secret Agent"
                    systemPrompt = "You are a secret agent"
                    modelId = 5L
                },
            )
        }

        private fun stubMcp(storedHeaders: String?, storedEnvParams: String?, status: Int = 1) {
            `when`(mcpBindingMapper.selectByAgentId(100L)).thenReturn(
                listOf(
                    AgentMcpBinding().apply {
                        agentId = 100L
                        mcpId = 7L
                    },
                ),
            )
            `when`(mcpServerMapper.selectByIds(listOf(7L))).thenReturn(
                listOf(
                    McpServer().apply {
                        id = 7L
                        name = "github-mcp"
                        type = "sse"
                        url = "https://example.com/sse"
                        headers = storedHeaders
                        envParams = storedEnvParams
                        this.status = status
                    },
                ),
            )
        }

        @Test
        @DisplayName("getAgentSpec - MCP headers 解密为扁平明文对象")
        fun `getAgentSpec should deliver MCP headers decrypted`() {
            stubWebSession()
            val storedHeaders = """[{"key":"Authorization","value":"ENC_B64","secret":true}]"""
            stubMcp(storedHeaders, null)
            `when`(secretFieldEncryptor.decryptToMap(storedHeaders))
                .thenReturn(mapOf("Authorization" to "Bearer token-abc"))

            val result = controller.getAgentSpec("web-secret")

            assertTrue(result.isSuccess())
            assertEquals("""{"Authorization":"Bearer token-abc"}""", result.data?.mcpDetails?.firstOrNull()?.headers)
        }

        @Test
        @DisplayName("getAgentSpec - MCP envParams 按 ToolEnvParamEntry 形态解密")
        fun `getAgentSpec should deliver MCP envParams decrypted`() {
            stubWebSession()
            val storedEnv = """[{"envParamName":"GITHUB_TOKEN","defaultValue":"ENC_B64","secret":true}]"""
            stubMcp(null, storedEnv)
            `when`(secretFieldEncryptor.decryptToolEnvParamsToMap(storedEnv))
                .thenReturn(mapOf("GITHUB_TOKEN" to "ghp_plain"))

            val result = controller.getAgentSpec("web-secret")

            assertTrue(result.isSuccess())
            assertEquals("""{"GITHUB_TOKEN":"ghp_plain"}""", result.data?.mcpDetails?.firstOrNull()?.envParams)
        }

        @Test
        @DisplayName("getAgentSpec - 未配置凭证时保持 null，不输出空对象")
        fun `getAgentSpec should keep blank config as null`() {
            stubWebSession()
            stubMcp("", "   ")

            val result = controller.getAgentSpec("web-secret")

            assertTrue(result.isSuccess())
            val mcp = result.data?.mcpDetails?.firstOrNull()
            assertNull(mcp?.headers)
            assertNull(mcp?.envParams)
            verifyNoInteractions(secretFieldEncryptor)
        }

        @Test
        @DisplayName("getAgentSpec - MCP status 随配置下发")
        fun `getAgentSpec should deliver MCP status`() {
            // Given - agent-service cannot see the table, so a disabled server must arrive disabled
            stubWebSession()
            stubMcp(null, null, status = 0)

            val result = controller.getAgentSpec("web-secret")

            assertTrue(result.isSuccess())
            assertEquals(0, result.data?.mcpDetails?.firstOrNull()?.status)
        }

        @Test
        @DisplayName("getAgentSpec - MCP 一次批量查询，悬空绑定两半都不出现")
        fun `getAgentSpec should fetch MCPs in one batch and drop an orphan binding`() {
            stubWebSession()
            `when`(mcpBindingMapper.selectByAgentId(100L)).thenReturn(
                listOf(
                    AgentMcpBinding().apply {
                        agentId = 100L
                        mcpId = 7L
                    },
                    // 服务行已删除，绑定还留着
                    AgentMcpBinding().apply {
                        agentId = 100L
                        mcpId = 88L
                    },
                ),
            )
            `when`(mcpServerMapper.selectByIds(listOf(7L, 88L))).thenReturn(
                listOf(
                    McpServer().apply {
                        id = 7L
                        name = "github-mcp"
                        type = "sse"
                    },
                ),
            )

            val data = controller.getAgentSpec("web-secret").data

            assertEquals(listOf(7L), data?.mcpDetails?.map { it.id })
            assertEquals("""[{"id":7,"env_bindings":[]}]""", data?.mcpList)
            verify(mcpServerMapper).selectByIds(listOf(7L, 88L))
            verify(mcpServerMapper, never()).selectById(anyLong())
        }

        @Test
        @DisplayName("getAgentSpec - 别租户的 MCP 服务两半都不下发")
        fun `getAgentSpec should drop an MCP server of another tenant`() {
            // Given - V23 之前存下的跨租户绑定：selectByIds 没有租户条件，只能拿 agent 自己的租户比
            stubWebSession()
            `when`(mcpBindingMapper.selectByAgentId(100L)).thenReturn(
                listOf(
                    AgentMcpBinding().apply {
                        agentId = 100L
                        mcpId = 7L
                    },
                ),
            )
            `when`(mcpServerMapper.selectByIds(listOf(7L))).thenReturn(
                listOf(
                    McpServer().apply {
                        id = 7L
                        name = "github-mcp"
                        type = "sse"
                        tenantId = 2L
                        headers = """[{"key":"Authorization","value":"ENC_B64","secret":true}]"""
                    },
                ),
            )

            val data = controller.getAgentSpec("web-secret").data

            assertEquals(emptyList<Long>(), data?.mcpDetails?.map { it.id })
            assertEquals("[]", data?.mcpList)
            // 连解密都不该发生：别租户的凭据不进下发内容
            verifyNoInteractions(secretFieldEncryptor)
        }

        @Test
        @DisplayName("getAgentSpec - 工具 httpHeaders 同样解密下发")
        fun `getAgentSpec should deliver tool httpHeaders decrypted`() {
            stubWebSession()
            val storedHeaders = """[{"key":"X-Api-Key","value":"ENC_B64","secret":true}]"""
            `when`(toolBindingMapper.selectByAgentId(100L)).thenReturn(
                listOf(
                    AgentToolBinding().apply {
                        agentId = 100L
                        toolId = 9L
                    },
                ),
            )
            `when`(agentToolMapper.selectByIds(listOf(9L))).thenReturn(
                listOf(
                    AgentTool().apply {
                        id = 9L
                        name = "http-call"
                        type = "HTTP"
                        httpUrl = "https://example.com/api"
                        httpHeaders = storedHeaders
                    },
                ),
            )
            `when`(secretFieldEncryptor.decryptToMap(storedHeaders))
                .thenReturn(mapOf("X-Api-Key" to "plain-key"))

            val result = controller.getAgentSpec("web-secret")

            assertTrue(result.isSuccess())
            assertEquals("""{"X-Api-Key":"plain-key"}""", result.data?.toolDetails?.firstOrNull()?.httpHeaders)
        }
    }

    @Nested
    @DisplayName("Agent 技能下发过滤")
    inner class AgentSkillDeliveryTests {

        /** 让 web 会话解析到 agent 100，并把绑定关系与技能内容分别 stub 到两个 mapper 上 */
        private fun stubAgentWithSkills(vararg skills: Skill) {
            val session = Session().apply {
                sessionId = "web-skill"
                agentId = 100L
                enableThink = 0
                enableSearch = 0
                enablePlan = 0
            }
            `when`(sessionMapper.selectBySessionIdAndStatus("web-skill", 1)).thenReturn(session)
            `when`(agentMapper.selectById(100L)).thenReturn(
                Agent().apply {
                    id = 100L
                    name = "Skill Agent"
                    systemPrompt = "You are a skill agent"
                    modelId = 5L
                },
            )
            `when`(skillBindingMapper.selectByAgentId(100L)).thenReturn(
                skills.map {
                    AgentSkillBinding().apply {
                        agentId = 100L
                        skillId = it.id
                    }
                },
            )
            `when`(skillMapper.selectByIds(skills.map { it.id })).thenReturn(skills.toList())
        }

        private fun skill(id: Long, name: String, status: Int) = Skill().apply {
            this.id = id
            this.name = name
            this.status = status
            skillmd = "# $name"
        }

        /** 让 agent 100 额外绑定一个 CLI，并把该 CLI 关联的技能 ID stub 好 */
        private fun stubCliWithSkills(cliId: Long, cliSkillIds: List<Long>) {
            `when`(cliBindingMapper.selectByAgentId(100L)).thenReturn(
                listOf(
                    AgentCliBinding().apply {
                        agentId = 100L
                        this.cliId = cliId
                    },
                ),
            )
            `when`(cliMapper.selectByIds(listOf(cliId))).thenReturn(
                listOf(
                    Cli().apply {
                        id = cliId
                        name = "cli-$cliId"
                        status = 1
                    },
                ),
            )
            `when`(cliSkillBindingMapper.selectByCliIds(listOf(cliId))).thenReturn(
                cliSkillIds.map {
                    CliSkillBinding().apply {
                        this.cliId = cliId
                        skillId = it
                    }
                },
            )
        }

        @Test
        @DisplayName("停用的技能既不进 skillDetails，也不进 skillList")
        fun `getAgentSpec should drop a disabled skill from both halves of the answer`() {
            // status = 0 有两个来源：运维在管理页手动停用，或重新导入时被 SkillContentScanner
            // 命中高危命令后降级待审核。两种情况下绑定关系都还在，闸门只能在这里生效
            stubAgentWithSkills(skill(11L, "enabled-skill", 1), skill(12L, "flagged-skill", 0))

            val data = controller.getAgentSpec("web-skill").data

            assertNotNull(data)
            assertEquals(listOf(11L), data?.skillDetails?.map { it.id })
            // skillList 若从绑定关系拼出来，就会把刚被丢弃的 12 也列进去，同一个响应两半自相矛盾
            assertEquals("11", data?.skillList)
        }

        @Test
        @DisplayName("绑定指向已删除技能时，其余技能照常下发")
        fun `getAgentSpec should keep the remaining skills when a binding points nowhere`() {
            stubAgentWithSkills(skill(21L, "live-skill", 1))
            // 悬空绑定：技能行已被删除，绑定表里还留着指向它的记录
            `when`(skillBindingMapper.selectByAgentId(100L)).thenReturn(
                listOf(
                    AgentSkillBinding().apply {
                        agentId = 100L
                        skillId = 21L
                    },
                    AgentSkillBinding().apply {
                        agentId = 100L
                        skillId = 999L
                    },
                ),
            )
            // 批量查询的结果里就是没有 999 这一行
            `when`(skillMapper.selectByIds(listOf(21L, 999L))).thenReturn(
                listOf(skill(21L, "live-skill", 1)),
            )

            val data = controller.getAgentSpec("web-skill").data

            assertNotNull(data)
            assertEquals(listOf(21L), data?.skillDetails?.map { it.id })
            assertEquals("21", data?.skillList)
        }

        @Test
        @DisplayName("CLI 关联的停用技能不下发")
        fun `getAgentSpec should drop a disabled skill merged in from a CLI`() {
            // 直接绑定那一路已有闸门，但技能还可以经 CLI 关联合并进 skillDetails；合并处不设同一
            // 道闸门，就会出现「全局注入路径丢掉它、CLI 路径照样加载它」的矛盾
            stubAgentWithSkills(skill(31L, "own-skill", 1))
            stubCliWithSkills(7L, listOf(31L, 32L))
            `when`(skillMapper.selectByIds(listOf(32L))).thenReturn(listOf(skill(32L, "cli-flagged", 0)))

            val data = controller.getAgentSpec("web-skill").data

            assertNotNull(data)
            assertEquals(listOf(31L), data?.skillDetails?.map { it.id })
            assertEquals("31", data?.skillList)
        }

        @Test
        @DisplayName("CLI 关联的技能与已绑定技能同名时，保留 agent 自己绑定的那一个")
        fun `getAgentSpec should keep the bound skill when a CLI skill shares its name`() {
            // 技能名只在仓库内唯一，而 harness 按 name 归并技能，两个同名技能都下发会让注册表
            // 与内存仓库对「谁生效」的判断不一致
            stubAgentWithSkills(skill(41L, "shared-name", 1))
            stubCliWithSkills(8L, listOf(42L))
            `when`(skillMapper.selectByIds(listOf(42L))).thenReturn(listOf(skill(42L, "shared-name", 1)))

            val data = controller.getAgentSpec("web-skill").data

            assertNotNull(data)
            assertEquals(listOf(41L), data?.skillDetails?.map { it.id })
            assertEquals("41", data?.skillList)
        }
    }

    /**
     * 工具下发口径：必须工具（is_required=1）不写绑定表，只能在下发阶段追加，且已存在历史绑定时
     * 不能重复下发；`status` 必须随 ToolDetailDto 一起下发，否则 agent-service 侧转换实体时拿到的
     * 是默认值 1，管理员停用工具形同无效。
     */
    @Nested
    @DisplayName("工具下发装配")
    inner class ToolDeliveryTests {

        private fun stubTool(
            id: Long,
            name: String,
            status: Int = 1,
            isRequired: Int = 0,
        ): AgentTool = AgentTool().apply {
            this.id = id
            this.name = name
            type = "BUILTIN"
            beanName = "demo-tool-box"
            this.status = status
            this.isRequired = isRequired
        }

        private fun stubWebSessionWithBindings(vararg toolIds: Long) {
            val session = Session().apply {
                sessionId = "web-tools"
                agentId = 100L
                enableThink = 0
                enableSearch = 0
                enablePlan = 0
            }
            `when`(sessionMapper.selectBySessionIdAndStatus("web-tools", 1)).thenReturn(session)
            `when`(agentMapper.selectById(100L)).thenReturn(
                Agent().apply {
                    id = 100L
                    name = "Tool Agent"
                    systemPrompt = "You are a tool agent"
                    modelId = 5L
                },
            )
            `when`(toolBindingMapper.selectByAgentId(100L)).thenReturn(
                toolIds.map {
                    AgentToolBinding().apply {
                        agentId = 100L
                        toolId = it
                    }
                },
            )
        }

        @Test
        @DisplayName("getAgentSpec - 没有绑定行时也必须工具照旧下发")
        fun `getAgentSpec should append required tools even without a binding`() {
            stubWebSessionWithBindings()
            val required = stubTool(20L, "contextTool", isRequired = 1)
            `when`(agentToolMapper.selectRequiredTools()).thenReturn(listOf(required))
            `when`(agentToolMapper.selectByIds(listOf(20L))).thenReturn(listOf(required))

            val data = controller.getAgentSpec("web-tools").data

            assertEquals(listOf(20L), data?.toolDetails?.map { it.id })
            // 必须工具没有绑定行，兼容用的 toolList JSON 里不出现它
            assertEquals("[]", data?.toolList)
        }

        @Test
        @DisplayName("getAgentSpec - 必须工具已有历史绑定时不重复下发")
        fun `getAgentSpec should not duplicate a required tool that already has a binding`() {
            stubWebSessionWithBindings(20L)
            val required = stubTool(20L, "contextTool", isRequired = 1)
            `when`(agentToolMapper.selectRequiredTools()).thenReturn(listOf(required))
            `when`(agentToolMapper.selectByIds(listOf(20L))).thenReturn(listOf(required))

            val data = controller.getAgentSpec("web-tools").data

            assertEquals(listOf(20L), data?.toolDetails?.map { it.id })
        }

        @Test
        @DisplayName("getAgentSpec - 停用工具带 status=0 下发")
        fun `getAgentSpec should deliver tool status`() {
            stubWebSessionWithBindings(21L)
            `when`(agentToolMapper.selectByIds(listOf(21L))).thenReturn(listOf(stubTool(21L, "disabledTool", status = 0)))

            val tool = controller.getAgentSpec("web-tools").data?.toolDetails?.firstOrNull()

            assertNotNull(tool)
            assertEquals(0, tool?.status)
        }
    }
}
