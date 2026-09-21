package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.constant.BuiltinRepository
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.EnvVariableService
import com.agnetix.harnax.admin.service.McpOAuthUserService
import com.agnetix.harnax.admin.service.McpStdioPolicy
import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.admin.util.SecretFieldEncryptor
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentCliBinding
import com.agnetix.harnax.entity.AgentMcpBinding
import com.agnetix.harnax.entity.AgentSkillBinding
import com.agnetix.harnax.entity.AgentTool
import com.agnetix.harnax.entity.AgentToolBinding
import com.agnetix.harnax.entity.ApiKeyEntity
import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.entity.Cli
import com.agnetix.harnax.entity.CliSkillBinding
import com.agnetix.harnax.entity.McpAuthTypes
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.entity.SkillRepository
import com.agnetix.harnax.entity.Team
import com.agnetix.harnax.entity.TeamMember
import com.agnetix.harnax.entity.TeamSkillBinding
import com.agnetix.harnax.entity.dto.ChannelSessionOwner
import com.agnetix.harnax.entity.dto.McpAccessTokenResponse
import com.agnetix.harnax.mapper.AgentCliBindingMapper
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
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
import com.agnetix.harnax.mapper.TeamMapper
import com.agnetix.harnax.mapper.TeamMemberMapper
import com.agnetix.harnax.mapper.TeamSkillBindingMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
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

    @Mock
    private lateinit var mcpOAuthUserService: McpOAuthUserService

    @Mock
    private lateinit var mcpStdioPolicy: McpStdioPolicy

    @Mock
    private lateinit var teamMapper: TeamMapper

    @Mock
    private lateinit var teamMemberMapper: TeamMemberMapper

    @Mock
    private lateinit var teamSkillBindingMapper: TeamSkillBindingMapper

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

    /**
     * 会话归属查询：router 的 `SessionAccessGuard` 就是拿这里给出的租户和调用方的租户做比对，
     * 所以「没有答案」（data 为 null）在它眼里等于放行。`chn-` 按设计不存在于 `session` 表里——
     * 它在 `channel` 行上，创建频道时盖章——早先这里只查 `session`，于是任何登录用户凭一个
     * `chn-{uuid}` 就能读走别租户的频道会话。
     *
     * 归属这一读不看 `active`：`deleteById` 是软删，行还在、租户还在、会话与沙箱都不清理，所以
     * 「查不到行」必须只剩「这行从没存在过」一种含义。配置那一读（`selectBySessionId`）照旧过滤
     * active，下面也钉住归属不走去它。这几条钉住修法与它不能碰坏的东西。
     */
    @Nested
    @DisplayName("会话归属查询接口")
    inner class GetSessionInfoTests {

        /** 归属那一读的返回：只有它查的三列，`active` 不在其中——它本来就不看这一列。 */
        private fun channelOwner(
            sessionId: String,
            tenantId: Long,
            agentId: Long,
        ) = ChannelSessionOwner().apply {
            this.sessionId = sessionId
            this.tenantId = tenantId
            this.agentId = agentId
        }

        @Test
        @DisplayName("getSessionInfo - chn 会话报出 channel 行的租户与 agent")
        fun `getSessionInfo reports the tenant that owns a channel session`() {
            val sessionId = "chn-11111111-2222-3333-4444-555555555555"
            `when`(channelMapper.selectOwnerBySessionId(sessionId)).thenReturn(channelOwner(sessionId, 7L, 3L))

            val result = controller.getSessionInfo(sessionId)

            assertTrue(result.isSuccess())
            val data = requireNotNull(result.data)
            assertEquals(sessionId, data.sessionId)
            assertEquals(7L, data.tenantId)
            assertEquals(3L, data.agentId)
            // 归属靠 channel 行，不去猜 agent 的名字与模型：这三个字段缺席只影响调用日志的富化列。
            assertNull(data.agentName)
            assertNull(data.modelId)
            assertNull(data.modelName)
            // 问错表就等于问不出答案：session 表里从来没有过 chn 行。
            verify(sessionMapper, never()).selectBySessionIdAndStatus(anyString(), anyInt())
        }

        @Test
        @DisplayName("getSessionInfo - 已软删的 channel 行照样报出租户")
        fun `getSessionInfo reports the tenant of a soft-deleted channel row`() {
            val sessionId = "chn-33333333-2222-3333-4444-555555555555"
            // 生产里就是这个形状：deleteById 只把 active 置 0，tenant_id 留在行上，而删频道既不清会话
            // 也不清沙箱。带 active 过滤的配置读查不到这一行（下面 stub 成 null 还原真实 SQL 的行为），
            // 所以归属若走那一条读，就等于回答「这会话没有主人」——router 读作放行。那不是缓存的五分钟
            // 窗口，而是直到 router 自己的会话绑定按 24 小时空闲过期为止，跨租户照读不误。
            `when`(channelMapper.selectBySessionId(sessionId)).thenReturn(null)
            `when`(channelMapper.selectOwnerBySessionId(sessionId)).thenReturn(channelOwner(sessionId, 7L, 3L))

            val data = requireNotNull(controller.getSessionInfo(sessionId).data) {
                "一个 active=0 的 channel 行仍然有主人：归属不能因为行被软删就答不出"
            }

            assertEquals(7L, data.tenantId)
            assertEquals(sessionId, data.sessionId)
            // 修法是新增一条不带 active 谓词的专用读，不是放宽共用的那一条：配置路径仍然看不见被删的行。
            verify(channelMapper, never()).selectBySessionId(anyString())
        }

        @Test
        @DisplayName("getSessionInfo - 没有任何 channel 行时仍回答未知")
        fun `getSessionInfo keeps a missing channel session unknown instead of guessing an owner`() {
            // 去掉 active 之后，「查不到」只剩这一种含义：chn id 是建频道时生成并随行一起插入的，没有行
            // 就是 admin 从没发过这个 id。仍然回答 null 而不是拒绝——那是这个端点对每个前缀既有的语义，
            // 猜一个租户则会凭陌生 id 判到别人头上。
            `when`(channelMapper.selectOwnerBySessionId("chn-does-not-exist")).thenReturn(null)

            val result = controller.getSessionInfo("chn-does-not-exist")

            assertTrue(result.isSuccess())
            assertNull(result.data)
            verify(sessionMapper, never()).selectBySessionIdAndStatus(anyString(), anyInt())
        }

        @Test
        @DisplayName("getSessionInfo - channel 行没有租户时照实回答 0，不给放行")
        fun `getSessionInfo does not turn a tenant-less channel row into no owner`() {
            // tenantId 为 null 是 router 读作「无法判定」的那个值，也就是自由通行证。行上写的是 0，
            // 就报 0：任何带租户的调用方因此成了跨租户调用方，被拒。
            `when`(
                channelMapper.selectOwnerBySessionId("chn-22222222-2222-3333-4444-555555555555"),
            ).thenReturn(channelOwner("chn-22222222-2222-3333-4444-555555555555", 0L, 3L))

            val data = requireNotNull(controller.getSessionInfo("chn-22222222-2222-3333-4444-555555555555").data)

            assertEquals(0L, data.tenantId)
        }

        @Test
        @DisplayName("getSessionInfo - web 会话仍只由 session 表回答")
        fun `getSessionInfo still resolves a web session from the session table`() {
            `when`(sessionMapper.selectBySessionIdAndStatus("web-abc123", 1)).thenReturn(
                Session().apply {
                    sessionId = "web-abc123"
                    agentId = 100L
                    name = "Test Agent"
                    modelId = 0L
                    tenantId = 9L
                },
            )

            val data = requireNotNull(controller.getSessionInfo("web-abc123").data)

            assertEquals(9L, data.tenantId)
            assertEquals(100L, data.agentId)
            assertEquals("Test Agent", data.agentName)
            verifyNoInteractions(channelMapper)
        }
    }

    @Nested
    @DisplayName("会话所属团队")
    inner class GetSessionTeamTests {

        @Test
        @DisplayName("getSessionTeam - 团队会话给出 teamId")
        fun `getSessionTeam should return the team of a team session`() {
            `when`(sessionMapper.selectBySessionIdAndStatus("web-team", 1)).thenReturn(
                Session().apply {
                    sessionId = "web-team"
                    teamId = 42L
                },
            )

            assertEquals(42L, controller.getSessionTeam("web-team").data)
        }

        @Test
        @DisplayName("getSessionTeam - 普通会话与未知会话都是 null")
        fun `getSessionTeam should return null without a team`() {
            `when`(sessionMapper.selectBySessionIdAndStatus("web-plain", 1)).thenReturn(
                Session().apply {
                    sessionId = "web-plain"
                    agentId = 3L
                },
            )

            assertNull(controller.getSessionTeam("web-plain").data)
            assertNull(controller.getSessionTeam("web-gone").data)
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
            // C1: the agent id is inside the session id, so no agent_task row is read for this.
            `when`(agentMapper.selectById(100L)).thenReturn(stubAgent())

            val result = controller.getAgentSpec("task-42-100-6f0b1a2c3d4e5f60718293a4b5c6d7e8")

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

        @Test
        @DisplayName("getAgentSpec - 团队会话被明确拒绝而不是去查 agent 0")
        fun `getAgentSpec should refuse a team session`() {
            // 团队会话不再有 agent_id（V34 起主管就是 team 行）。落到通用解析上就会去查
            // agent 0，报错也指不到真正该走的那条路
            `when`(sessionMapper.selectBySessionIdAndStatus("web-team", 1)).thenReturn(
                Session().apply {
                    sessionId = "web-team"
                    teamId = 42L
                    tenantId = 7L
                },
            )

            val result = controller.getAgentSpec("web-team")

            assertFalse(result.isSuccess())
            assertTrue(result.message.contains("team-spec"), result.message)
            verify(agentMapper, never()).selectById(anyLong())
        }
    }

    @Nested
    @DisplayName("团队 spec 下发")
    inner class GetTeamSpecTests {

        private fun team(): Team = Team().apply {
            id = 42L
            name = "Research"
            tenantId = 7L
            status = 1
            systemPrompt = "你是本次协作的负责人，只拆解、委派与验收"
            modelId = 11L
        }

        private fun stubTeamSession() {
            `when`(sessionMapper.selectBySessionIdAndStatus("web-team", 1)).thenReturn(
                Session().apply {
                    sessionId = "web-team"
                    teamId = 42L
                    tenantId = 7L
                },
            )
        }

        private fun stubMember() {
            `when`(teamMemberMapper.selectByTeamId(42L)).thenReturn(
                listOf(
                    TeamMember().apply {
                        teamId = 42L
                        memberAgentId = 3L
                        delegationDescription = "gathers"
                    },
                ),
            )
            `when`(agentMapper.selectById(3L)).thenReturn(
                Agent().apply {
                    id = 3L
                    name = "Researcher"
                    description = "Researcher desc"
                    systemPrompt = "You research"
                    modelId = 12L
                    tenantId = 7L
                    status = 1
                },
            )
        }

        @Test
        @DisplayName("getTeamSpec - 主管配置取自 team 行")
        fun `getTeamSpec should build the lead spec from the team row`() {
            stubTeamSession()
            `when`(teamMapper.selectById(42L)).thenReturn(team())
            stubMember()
            `when`(teamSkillBindingMapper.selectByTeamId(42L)).thenReturn(
                listOf(
                    TeamSkillBinding().apply {
                        teamId = 42L
                        skillId = 100L
                    },
                ),
            )
            `when`(skillMapper.selectByIds(listOf(100L))).thenReturn(
                listOf(
                    Skill().apply {
                        id = 100L
                        name = "pdf-report"
                        description = "Reads pdf"
                        skillmd = "# PDF"
                        tenantId = 7L
                        status = 1
                    },
                ),
            )

            val data = requireNotNull(controller.getTeamSpec("web-team").data)

            assertEquals(42L, data.teamId)
            assertEquals(7L, data.tenantId)
            assertEquals(0L, data.lead.agentId)
            assertEquals("Research", data.lead.agentName)
            assertEquals("你是本次协作的负责人，只拆解、委派与验收", data.lead.systemPrompt)
            assertEquals(11L, data.lead.modelId)
            assertEquals(listOf("pdf-report"), data.lead.skillDetails.map { it.name })
            assertEquals("100", data.lead.skillList)
            assertEquals(listOf(3L), data.members.map { it.memberAgentId })
            assertEquals("Researcher", data.members.first().spec.agentName)
        }

        @Test
        @DisplayName("getTeamSpec - 主管不下发 tool、MCP、CLI 与必须工具")
        fun `getTeamSpec should deliver no tool mcp or cli for the lead`() {
            // 主管无 shell 无沙箱（设计 D5）。必须工具在 agent 侧是「任何配置都删不掉」，团队侧
            // 根本没有这类配置可删，所以它也不该被追加进来
            stubTeamSession()
            `when`(teamMapper.selectById(42L)).thenReturn(team())
            stubMember()
            `when`(agentToolMapper.selectRequiredTools()).thenReturn(
                listOf(
                    AgentTool().apply {
                        id = 88L
                        name = "mandatory"
                        status = 1
                    },
                ),
            )

            val lead = requireNotNull(controller.getTeamSpec("web-team").data).lead

            assertTrue(lead.toolDetails.isEmpty())
            assertEquals("[]", lead.toolList)
            assertTrue(lead.mcpDetails.isEmpty())
            assertTrue(lead.cliDetails.isEmpty())
        }
    }

    /**
     * 敏感配置的下发口径：agent-service 不持有 AES 密钥，所以 `mcp_server.headers`、
     * `mcp_server.envParams` 必须在下发前就解密成扁平明文对象。
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
        @DisplayName("getAgentSpec - 停用的 MCP 不下发，其凭证也不解密")
        fun `getAgentSpec should not deliver a disabled MCP server`() {
            // Given - 闸门原先只由 agent-service 执行，管理端仍把停用行解密后的凭证送上网络
            stubWebSession()
            val storedHeaders = """[{"key":"Authorization","value":"ENC_B64","secret":true}]"""
            stubMcp(storedHeaders, null, status = 0)

            val result = controller.getAgentSpec("web-secret")

            assertTrue(result.isSuccess())
            assertEquals(0, result.data?.mcpDetails?.size)
            // 扣在管理端才谈得上少解密：行都不发，就没有必要把它还原成明文
            verifyNoInteractions(secretFieldEncryptor)
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
        private fun stubCliWithSkills(
            cliId: Long,
            cliSkillIds: List<Long>,
            status: Int = 1,
        ) {
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
                        this.status = status
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
        @DisplayName("CLI 只透传绑定的技能 ID，不并入 skillDetails")
        fun `getAgentSpec should carry CLI skill ids without merging them into skillDetails`() {
            // 内置 CLI 技能在 agent 配置页既不展示也不可勾选，「选没选中这个 CLI」就是唯一开关。
            // 开关由 agent-service 判定（它才知道本次注入了哪些内置技能），admin 只负责把 ID 带下去
            stubAgentWithSkills(skill(31L, "own-skill", 1))
            stubCliWithSkills(7L, listOf(32L))
            // 合并逻辑已删、这行桩生产路径不再触发，但刻意留着：谁把合并加回来，32 就会出现在
            // skillDetails 里把用例弄红——没有它，回归会伪装成通过
            `when`(skillMapper.selectByIds(listOf(32L))).thenReturn(listOf(skill(32L, "cli-skill", 1)))

            val data = controller.getAgentSpec("web-skill").data

            assertNotNull(data)
            assertEquals(listOf(31L), data?.skillDetails?.map { it.id })
            assertEquals("31", data?.skillList)
            assertEquals(listOf(32L), data?.cliDetails?.single()?.skillIds)
        }

        @Test
        @DisplayName("停用的 CLI 不下发，其技能绑定也随之消失")
        fun `getAgentSpec should drop a disabled CLI and its skill ids`() {
            // 唯一开关就在这一行闸门上：CLI 一旦停用，它关联的内置技能既装不进沙箱，
            // 也不该再被 agent-service 按 skillIds 捞进 prompt
            stubAgentWithSkills(skill(35L, "own-skill", 1))
            stubCliWithSkills(9L, listOf(36L), status = 0)
            // 同上一条用例：桩不再被生产路径触发，留着是为了让「合并被加回来」这种回归仍然显形
            `when`(skillMapper.selectByIds(listOf(36L))).thenReturn(listOf(skill(36L, "cli-skill", 1)))

            val data = controller.getAgentSpec("web-skill").data

            assertNotNull(data)
            assertTrue(data?.cliDetails?.isEmpty() ?: false)
            assertEquals(listOf(35L), data?.skillDetails?.map { it.id })
        }
    }

    /**
     * `cli.env_params` 是 CLI 登记页采集的环境参数声明（含默认值，secret 条目在库里是密文），
     * 而 `agent_cli_binding.env_bindings` 是某个 agent 对其中若干参数的显式赋值。沙箱只能从
     * `cliDetails[].envBindings` 拿到值，所以下发时必须把两者合成一份，否则「装了 CLI 但拿不到
     * 它的凭证」——install 脚本照跑、check 照过，命令一敲就报未登录。
     */
    @Nested
    @DisplayName("CLI 环境参数下发")
    inner class CliEnvDeliveryTests {

        /**
         * @param bindingEnv agent 侧显式绑定的 JSON，null 表示当前 agent 表单什么都没采集
         * @param declaredEnv CLI 自身的声明 JSON
         */
        private fun stubCliEnv(
            bindingEnv: String?,
            declaredEnv: String?,
        ) {
            val session = Session().apply {
                sessionId = "web-cli-env"
                agentId = 100L
                enableThink = 0
                enableSearch = 0
                enablePlan = 0
            }
            `when`(sessionMapper.selectBySessionIdAndStatus("web-cli-env", 1)).thenReturn(session)
            `when`(agentMapper.selectById(100L)).thenReturn(
                Agent().apply {
                    id = 100L
                    name = "CLI Env Agent"
                    systemPrompt = "x"
                    modelId = 5L
                },
            )
            `when`(cliBindingMapper.selectByAgentId(100L)).thenReturn(
                listOf(
                    AgentCliBinding().apply {
                        agentId = 100L
                        cliId = 7L
                        envBindings = bindingEnv
                    },
                ),
            )
            `when`(cliMapper.selectByIds(listOf(7L))).thenReturn(
                listOf(
                    Cli().apply {
                        id = 7L
                        name = "gh"
                        envParams = declaredEnv
                    },
                ),
            )
            `when`(cliSkillBindingMapper.selectByCliIds(listOf(7L))).thenReturn(emptyList())
        }

        private fun deliveredEnv(): Map<String, String> {
            val delivered = controller.getAgentSpec("web-cli-env").data?.cliDetails?.single()?.envBindings.orEmpty()
            return delivered.associate { it.getValue("envKey") to it.getValue("envValue") }
        }

        @Test
        @DisplayName("agent 未采集任何值时，下发 CLI 声明的默认值")
        fun `getAgentSpec should fall back to the CLI declared defaults`() {
            val declared = """
                [{"envParamName":"GH_TOKEN","defaultValue":"ENC_B64","secret":true},
                 {"envParamName":"GH_HOST","defaultValue":"github.com","secret":false}]
            """.trimIndent()
            stubCliEnv(null, declared)
            `when`(secretFieldEncryptor.decryptToolEnvParamsToMap(declared))
                .thenReturn(mapOf("GH_TOKEN" to "ghp_plain", "GH_HOST" to "github.com"))

            assertEquals(
                mapOf("GH_TOKEN" to "ghp_plain", "GH_HOST" to "github.com"),
                deliveredEnv(),
            )
        }

        @Test
        @DisplayName("agent 侧显式值优先，未被覆盖的声明照常补上")
        fun `getAgentSpec should let the per-agent value win key by key`() {
            // 合并只能按 key 做：整份互斥的话，表单一旦开始采集其中一个参数，其余参数的默认值就全丢了
            val binding = """[{"envKey":"GH_HOST","customValue":"ghes.internal"}]"""
            val declared = """
                [{"envParamName":"GH_HOST","defaultValue":"github.com","secret":false},
                 {"envParamName":"GH_TOKEN","defaultValue":"ENC_B64","secret":true}]
            """.trimIndent()
            stubCliEnv(binding, declared)
            `when`(secretFieldEncryptor.decryptToolEnvParamsToMap(declared))
                .thenReturn(mapOf("GH_HOST" to "github.com", "GH_TOKEN" to "ghp_plain"))

            assertEquals(
                mapOf("GH_HOST" to "ghes.internal", "GH_TOKEN" to "ghp_plain"),
                deliveredEnv(),
            )
        }

        @Test
        @DisplayName("声明了参数但没有默认值时，不下发空值")
        fun `getAgentSpec should skip a declaration that carries no value`() {
            // 沙箱里 `GH_TOKEN=` 与「没有这个变量」并不等价：前者会让 gh cli 认为已配置再去读一个空 token
            val declared = """[{"envParamName":"GH_TOKEN","defaultValue":null,"secret":true}]"""
            stubCliEnv(null, declared)
            `when`(secretFieldEncryptor.decryptToolEnvParamsToMap(declared))
                .thenReturn(mapOf("GH_TOKEN" to ""))

            assertEquals(mapOf<String, String>(), deliveredEnv())
        }
    }

    /**
     * `/builtin-skills` 现在是 CLI 关联技能内容的唯一来源（agent-service 每次 resolve 现取），
     * 内置技能的 `status` 闸门也就只剩这一处：运维直接改库降级一个内置技能，全靠这里拦住。
     */
    @Nested
    @DisplayName("内置技能下发")
    inner class BuiltinSkillsDeliveryTests {

        private fun builtinSkill(id: Long, name: String, status: Int) = Skill().apply {
            this.id = id
            this.name = name
            this.status = status
            repositoryId = 3L
            skillmd = "# $name"
        }

        @Test
        @DisplayName("被停用的内置技能不下发")
        fun `getBuiltinSkills should deliver only enabled skills`() {
            `when`(skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS)).thenReturn(
                SkillRepository().apply {
                    id = 3L
                    name = BuiltinRepository.CLI_SKILLS
                },
            )
            `when`(skillMapper.selectByRepositoryId(3L)).thenReturn(
                listOf(builtinSkill(41L, "harnax-cli", 1), builtinSkill(42L, "flagged-cli", 0)),
            )

            val data = controller.getBuiltinSkills().data

            assertEquals(listOf(41L), data?.map { it.id })
        }

        @Test
        @DisplayName("内置仓库不存在时返回空列表，不报错")
        fun `getBuiltinSkills should return an empty list when the repository is missing`() {
            `when`(skillRepositoryMapper.selectBuiltinRepository(BuiltinRepository.CLI_SKILLS)).thenReturn(null)

            val result = controller.getBuiltinSkills()

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() ?: false)
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

    /**
     * MCP 的鉴权方式与执行面在下发时的口径：`authType` 必须随配置给出，运行时才知道这个服务要按人
     * 挂 token 还是用静态头；stdio 行在开关关闭时整条不下发——它是让 agent-service 起一个进程。
     */
    @Nested
    @DisplayName("MCP 鉴权类型下发与 stdio 扣留")
    inner class McpAuthDeliveryTests {

        private fun stubWebSessionWithMcps(vararg servers: McpServer) {
            val session = Session().apply {
                sessionId = "web-mcp-auth"
                agentId = 100L
                enableThink = 0
                enableSearch = 0
                enablePlan = 0
            }
            `when`(sessionMapper.selectBySessionIdAndStatus("web-mcp-auth", 1)).thenReturn(session)
            `when`(agentMapper.selectById(100L)).thenReturn(
                Agent().apply {
                    id = 100L
                    name = "MCP Agent"
                    systemPrompt = "You are an MCP agent"
                    modelId = 5L
                },
            )
            `when`(mcpBindingMapper.selectByAgentId(100L)).thenReturn(
                servers.map {
                    AgentMcpBinding().apply {
                        agentId = 100L
                        mcpId = it.id
                    }
                },
            )
            `when`(mcpServerMapper.selectByIds(servers.map { it.id })).thenReturn(servers.toList())
        }

        @Test
        @DisplayName("getAgentSpec - authType 随 MCP 配置一起下发")
        fun `getAgentSpec should deliver the auth type`() {
            stubWebSessionWithMcps(
                McpServer().apply {
                    id = 7L
                    name = "oauth-mcp"
                    type = "streamablehttp"
                    authType = McpAuthTypes.OAUTH2
                },
            )

            val mcp = controller.getAgentSpec("web-mcp-auth").data?.mcpDetails?.firstOrNull()

            // 少了这个字段，按人的 token 在运行侧无处可挂，而 OAuth 服务看起来和一个静态头服务一样
            assertEquals(McpAuthTypes.OAUTH2, mcp?.authType)
        }

        @Test
        @DisplayName("getAgentSpec - stdio 关闭时两半都不出现")
        fun `getAgentSpec should hold a stdio server back from both halves`() {
            stubWebSessionWithMcps(
                McpServer().apply {
                    id = 7L
                    name = "local-process"
                    type = "stdio"
                    command = "npx -y some-package"
                },
                McpServer().apply {
                    id = 8L
                    name = "remote-sse"
                    type = "sse"
                },
            )
            `when`(mcpStdioPolicy.isStdio("stdio")).thenReturn(true)

            val data = controller.getAgentSpec("web-mcp-auth").data

            assertEquals(listOf(8L), data?.mcpDetails?.map { it.id })
            // 与技能那两半同理：兼容用的 mcpList 若从绑定关系直接拼，就会把扣下的进程列进去让运行侧去起
            assertEquals("""[{"id":8,"env_bindings":[]}]""", data?.mcpList)
        }

        @Test
        @DisplayName("getAgentSpec - 停用的 MCP 两半都不出现")
        fun `getAgentSpec should hold a disabled MCP server back from both halves`() {
            stubWebSessionWithMcps(
                McpServer().apply {
                    id = 7L
                    name = "paused-server"
                    type = "streamablehttp"
                    status = 0
                },
                McpServer().apply {
                    id = 8L
                    name = "running-server"
                    type = "streamablehttp"
                    status = 1
                },
            )
            // 停用行带着一条 env 绑定：mcpList 是 ToolEnvContext 的来源，漏下去的值会被所有工具按名读到
            `when`(mcpBindingMapper.selectByAgentId(100L)).thenReturn(
                listOf(
                    AgentMcpBinding().apply {
                        agentId = 100L
                        mcpId = 7L
                        envBindings = """[{"envKey":"WEATHER_KEY","customValue":"from-paused-server"}]"""
                    },
                    AgentMcpBinding().apply {
                        agentId = 100L
                        mcpId = 8L
                    },
                ),
            )

            val data = controller.getAgentSpec("web-mcp-auth").data

            assertEquals(listOf(8L), data?.mcpDetails?.map { it.id })
            // 整条绑定一起扣下，而不只是它的配置：见 InternalApiController 里 heldDisabled 的注释
            assertEquals("""[{"id":8,"env_bindings":[]}]""", data?.mcpList)
        }

        @Test
        @DisplayName("getAgentSpec - stdio 开关打开后照常下发")
        fun `getAgentSpec should deliver a stdio server once the switch is on`() {
            stubWebSessionWithMcps(
                McpServer().apply {
                    id = 7L
                    name = "local-process"
                    type = "stdio"
                },
            )
            `when`(mcpStdioPolicy.enabled).thenReturn(true)

            val data = controller.getAgentSpec("web-mcp-auth").data

            assertEquals(listOf(7L), data?.mcpDetails?.map { it.id })
            // 开关是唯一依据：关了才逐行判断类型，开着时连类型都不必问
            verify(mcpStdioPolicy, never()).isStdio(anyString())
        }
    }

    @Nested
    @DisplayName("MCP 按人换发访问令牌")
    inner class McpAccessTokenTests {

        @Test
        @DisplayName("getMcpAccessToken - 只凭 sessionId 换发并原样带回令牌")
        fun `getMcpAccessToken should ask for the session owner token`() {
            `when`(mcpOAuthUserService.accessToken("web-abc", 7L))
                .thenReturn(McpAccessTokenResponse(accessToken = "at-1", expiresAtEpochSecond = 123L))

            val result = controller.getMcpAccessToken(
                InternalApiController.McpAccessTokenRequest(sessionId = "web-abc", mcpId = 7L),
            )

            assertTrue(result.isSuccess())
            assertEquals("at-1", result.data?.accessToken)
            assertEquals(123L, result.data?.expiresAtEpochSecond)
        }

        @Test
        @DisplayName("getMcpAccessToken - 授权已失效时保留 401 而非压成 500")
        fun `getMcpAccessToken should keep the 401 of a lost grant`() {
            `when`(mcpOAuthUserService.accessToken("web-abc", 7L))
                .thenThrow(BizException(401, "This MCP server is not authorized for your account yet"))

            val result = controller.getMcpAccessToken(
                InternalApiController.McpAccessTokenRequest(sessionId = "web-abc", mcpId = 7L),
            )

            // 401 与 503 的分别是运行侧唯一的判断依据：前者要用户重新授权，后者只要重试一次
            assertEquals(401, result.code)
        }

        @Test
        @DisplayName("getMcpAccessToken - 空 sessionId 直接拒绝，不查库")
        fun `getMcpAccessToken should reject a blank session id`() {
            val result = controller.getMcpAccessToken(
                InternalApiController.McpAccessTokenRequest(sessionId = "  ", mcpId = 7L),
            )

            assertEquals(400, result.code)
            verifyNoInteractions(mcpOAuthUserService)
        }
    }
}
