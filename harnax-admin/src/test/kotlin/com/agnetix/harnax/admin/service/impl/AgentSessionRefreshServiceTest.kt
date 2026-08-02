package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentCliBinding
import com.agnetix.harnax.entity.ApiKeyEntity
import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.mapper.AgentCliBindingMapper
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.SessionMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import java.util.concurrent.TimeUnit

/**
 * AgentSessionRefreshService 单元测试
 * 使用 MockWebServer 模拟 harnax-router 的 HTTP 端点
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentSessionRefreshServiceTest {

    @Mock
    private lateinit var channelMapper: ChannelMapper

    @Mock
    private lateinit var sessionMapper: SessionMapper

    @Mock
    private lateinit var apiKeyMapper: ApiKeyMapper

    @Mock
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var cliBindingMapper: AgentCliBindingMapper

    /** 使用真实 AesUtil 加解密 SYSTEM key */
    private val aesUtil = AesUtil("test-secret-key-for-unit-tests!!")

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun createService(routerUrl: String = server.url("/").toString().removeSuffix("/")): AgentSessionRefreshService = AgentSessionRefreshService(
        channelMapper,
        sessionMapper,
        apiKeyMapper,
        agentMapper,
        cliBindingMapper,
        aesUtil,
        routerUrl,
    )

    /** stub 出一个可正常解密的 SYSTEM key */
    private fun stubSystemApiKey(rawKey: String = "system-raw-api-key") {
        val entity = ApiKeyEntity().apply {
            id = 9L
            name = "system_channel-service"
            rawKeyEncrypted = aesUtil.encrypt(rawKey)
        }
        `when`(apiKeyMapper.selectSystemKeyByServiceName("channel-service")).thenReturn(entity)
    }

    @Nested
    @DisplayName("查询关联会话测试")
    inner class ListRelatedSessionsTests {

        @Test
        @DisplayName("listRelatedSessions - 返回渠道会话和Web会话")
        fun `listRelatedSessions should return channel and web sessions`() {
            // Given
            val channel = Channel().apply {
                id = 1L
                name = "微信客服"
                type = "wechat"
                sessionId = "ch-session-1"
            }
            val webSession = Session().apply {
                sessionId = "web-session-1"
                title = "对话1"
                active = 1
            }
            `when`(channelMapper.selectByAgentId(10L)).thenReturn(listOf(channel))
            `when`(sessionMapper.selectByAgentId(10L)).thenReturn(listOf(webSession))

            // When
            val result = createService().listRelatedSessions(10L)

            // Then
            assertEquals(2, result.size)
            assertEquals("ch-session-1", result[0].sessionId)
            assertEquals("channel", result[0].sourceType)
            assertEquals("微信客服 (wechat)", result[0].sourceName)
            assertEquals("web-session-1", result[1].sessionId)
            assertEquals("session", result[1].sourceType)
            assertEquals("对话1", result[1].sourceName)
        }

        @Test
        @DisplayName("listRelatedSessions - 跳过sessionId为空的渠道")
        fun `listRelatedSessions should skip channels with blank sessionId`() {
            // Given
            val channel = Channel().apply {
                name = "空会话渠道"
                type = "http"
                sessionId = ""
            }
            `when`(channelMapper.selectByAgentId(10L)).thenReturn(listOf(channel))
            `when`(sessionMapper.selectByAgentId(10L)).thenReturn(emptyList())

            // When
            val result = createService().listRelatedSessions(10L)

            // Then
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("listRelatedSessions - 跳过非active的Web会话")
        fun `listRelatedSessions should skip inactive web sessions`() {
            // Given
            val inactive = Session().apply {
                sessionId = "web-session-1"
                title = "已删除"
                active = 0
            }
            `when`(channelMapper.selectByAgentId(10L)).thenReturn(emptyList())
            `when`(sessionMapper.selectByAgentId(10L)).thenReturn(listOf(inactive))

            // When
            val result = createService().listRelatedSessions(10L)

            // Then
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("listRelatedSessions - Web会话标题为空时用sessionId兜底")
        fun `listRelatedSessions should fall back to sessionId when title blank`() {
            // Given
            val session = Session().apply {
                sessionId = "web-session-2"
                title = ""
                active = 1
            }
            `when`(channelMapper.selectByAgentId(10L)).thenReturn(emptyList())
            `when`(sessionMapper.selectByAgentId(10L)).thenReturn(listOf(session))

            // When
            val result = createService().listRelatedSessions(10L)

            // Then
            assertEquals(1, result.size)
            assertEquals("web-session-2", result[0].sourceName)
        }

        @Test
        @DisplayName("listRelatedSessions - 无关联数据返回空列表")
        fun `listRelatedSessions should return empty list when nothing related`() {
            `when`(channelMapper.selectByAgentId(10L)).thenReturn(emptyList())
            `when`(sessionMapper.selectByAgentId(10L)).thenReturn(emptyList())

            assertTrue(createService().listRelatedSessions(10L).isEmpty())
        }
    }

    @Nested
    @DisplayName("刷新会话测试")
    inner class RefreshSessionsTests {

        @Test
        @DisplayName("refreshSessions - 空列表直接返回且不发请求")
        fun `refreshSessions should return empty list for empty input`() {
            // When
            val result = createService().refreshSessions(emptyList())

            // Then
            assertTrue(result.isEmpty())
            assertEquals(0, server.requestCount)
        }

        @Test
        @DisplayName("refreshSessions - 无SYSTEM key时全部失败且不发请求")
        fun `refreshSessions should fail all when no system api key`() {
            // Given
            `when`(apiKeyMapper.selectSystemKeyByServiceName("channel-service")).thenReturn(null)

            // When
            val result = createService().refreshSessions(listOf("s1", "s2"))

            // Then
            assertEquals(2, result.size)
            assertTrue(result.all { !it.success })
            assertTrue(result.all { it.error == "No system API key available" })
            assertEquals(0, server.requestCount)
        }

        @Test
        @DisplayName("refreshSessions - key解密失败时全部失败")
        fun `refreshSessions should fail all when key decryption fails`() {
            // Given
            val badEntity = ApiKeyEntity().apply {
                rawKeyEncrypted = "not-valid-base64-!!!"
            }
            `when`(apiKeyMapper.selectSystemKeyByServiceName("channel-service")).thenReturn(badEntity)

            // When
            val result = createService().refreshSessions(listOf("s1"))

            // Then
            assertEquals(1, result.size)
            assertFalse(result[0].success)
            assertEquals("No system API key available", result[0].error)
            assertEquals(0, server.requestCount)
        }

        @Test
        @DisplayName("refreshSessions - 成功推送REFRESH命令并携带API key")
        fun `refreshSessions should post refresh command with api key header`() {
            // Given
            stubSystemApiKey("system-raw-api-key")
            server.enqueue(MockResponse().setResponseCode(200))

            // When
            val result = createService().refreshSessions(listOf("session-abc"))

            // Then
            assertEquals(1, result.size)
            assertTrue(result[0].success)
            assertNull(result[0].error)
            assertEquals("session-abc", result[0].sessionId)

            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("POST", request!!.method)
            assertEquals("/api/router/agent/command", request.path)
            assertEquals("system-raw-api-key", request.getHeader("X-Api-Key"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"sessionId\":\"session-abc\""), "body: $body")
            assertTrue(body.contains("\"command\":\"REFRESH\""), "body: $body")
            assertTrue(body.contains("\"type\":\"COMMAND\""), "body: $body")
        }

        @Test
        @DisplayName("refreshSessions - 单个失败不中断批量处理")
        fun `refreshSessions should continue batch when one session fails`() {
            // Given
            stubSystemApiKey()
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(500).setBody("router error"))
            server.enqueue(MockResponse().setResponseCode(200))

            // When
            val result = createService().refreshSessions(listOf("s1", "s2", "s3"))

            // Then
            assertEquals(3, result.size)
            assertTrue(result[0].success)
            assertFalse(result[1].success)
            assertNotNull(result[1].error)
            assertTrue(result[2].success)
            assertEquals(3, server.requestCount)
        }

        @Test
        @DisplayName("refreshSessions - router不可达时返回失败结果")
        fun `refreshSessions should return failure when router unreachable`() {
            // Given
            stubSystemApiKey()
            val deadUrl = server.url("/").toString().removeSuffix("/")
            server.shutdown() // 关闭端口模拟 router 宕机

            // When
            val result = createService(routerUrl = deadUrl).refreshSessions(listOf("s1"))

            // Then
            assertEquals(1, result.size)
            assertFalse(result[0].success)
            assertNotNull(result[0].error)
        }
    }

    @Nested
    @DisplayName("按CLI查询关联Agent测试")
    inner class ListAgentsByCliTests {

        private fun binding(agentId: Long) = AgentCliBinding().apply {
            this.agentId = agentId
            this.cliId = 5L
        }

        @Test
        @DisplayName("listAgentsByCli - 返回引用该CLI的Agent信息")
        fun `listAgentsByCli should return agents referencing the cli`() {
            // Given
            `when`(cliBindingMapper.selectByCliId(5L)).thenReturn(listOf(binding(10L), binding(11L)))
            `when`(agentMapper.selectById(10L)).thenReturn(
                Agent().apply {
                    id = 10L
                    name = "客服Agent"
                    status = 1
                },
            )
            `when`(agentMapper.selectById(11L)).thenReturn(
                Agent().apply {
                    id = 11L
                    name = "翻译Agent"
                    status = 0
                },
            )

            // When
            val result = createService().listAgentsByCli(5L)

            // Then
            assertEquals(2, result.size)
            assertEquals("客服Agent", result[0].agentName)
            assertEquals(1, result[0].status)
            assertEquals("翻译Agent", result[1].agentName)
            assertEquals(0, result[1].status)
        }

        @Test
        @DisplayName("listAgentsByCli - 去重相同Agent的多条绑定")
        fun `listAgentsByCli should deduplicate agent ids`() {
            // Given
            `when`(cliBindingMapper.selectByCliId(5L)).thenReturn(listOf(binding(10L), binding(10L)))
            `when`(agentMapper.selectById(10L)).thenReturn(
                Agent().apply {
                    id = 10L
                    name = "客服Agent"
                    status = 1
                },
            )

            // When
            val result = createService().listAgentsByCli(5L)

            // Then
            assertEquals(1, result.size)
        }

        @Test
        @DisplayName("listAgentsByCli - 跳过已不存在的Agent")
        fun `listAgentsByCli should skip missing agents`() {
            // Given
            `when`(cliBindingMapper.selectByCliId(5L)).thenReturn(listOf(binding(10L)))
            `when`(agentMapper.selectById(10L)).thenReturn(null)

            // When
            val result = createService().listAgentsByCli(5L)

            // Then
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("listAgentsByCli - 无绑定返回空列表")
        fun `listAgentsByCli should return empty list when no bindings`() {
            `when`(cliBindingMapper.selectByCliId(5L)).thenReturn(emptyList())

            assertTrue(createService().listAgentsByCli(5L).isEmpty())
        }
    }

    @Nested
    @DisplayName("按CLI查询关联会话测试")
    inner class ListSessionsByCliTests {

        @Test
        @DisplayName("listSessionsByCli - 汇总所有绑定Agent的会话并带上Agent名称")
        fun `listSessionsByCli should aggregate sessions of bound agents with agent name`() {
            // Given
            `when`(cliBindingMapper.selectByCliId(5L)).thenReturn(
                listOf(
                    AgentCliBinding().apply {
                        agentId = 10L
                        cliId = 5L
                    },
                ),
            )
            `when`(agentMapper.selectById(10L)).thenReturn(
                Agent().apply {
                    id = 10L
                    name = "客服Agent"
                    status = 1
                },
            )
            `when`(channelMapper.selectByAgentId(10L)).thenReturn(
                listOf(
                    Channel().apply {
                        name = "微信客服"
                        type = "wechat"
                        sessionId = "ch-session-1"
                    },
                ),
            )
            `when`(sessionMapper.selectByAgentId(10L)).thenReturn(
                listOf(
                    Session().apply {
                        sessionId = "web-session-1"
                        title = "对话1"
                        active = 1
                    },
                ),
            )

            // When
            val result = createService().listSessionsByCli(5L)

            // Then
            assertEquals(2, result.size)
            assertTrue(result.all { it.agentName == "客服Agent" })
            assertEquals(setOf("ch-session-1", "web-session-1"), result.map { it.sessionId }.toSet())
        }

        @Test
        @DisplayName("listSessionsByCli - 按sessionId去重")
        fun `listSessionsByCli should deduplicate by sessionId`() {
            // Given: 两个 Agent 关联了同一个 sessionId 的渠道
            `when`(cliBindingMapper.selectByCliId(5L)).thenReturn(
                listOf(
                    AgentCliBinding().apply {
                        agentId = 10L
                        cliId = 5L
                    },
                    AgentCliBinding().apply {
                        agentId = 11L
                        cliId = 5L
                    },
                ),
            )
            `when`(agentMapper.selectById(10L)).thenReturn(
                Agent().apply {
                    id = 10L
                    name = "A1"
                    status = 1
                },
            )
            `when`(agentMapper.selectById(11L)).thenReturn(
                Agent().apply {
                    id = 11L
                    name = "A2"
                    status = 1
                },
            )
            val sharedChannel = Channel().apply {
                name = "共享渠道"
                type = "http"
                sessionId = "shared-session"
            }
            `when`(channelMapper.selectByAgentId(10L)).thenReturn(listOf(sharedChannel))
            `when`(channelMapper.selectByAgentId(11L)).thenReturn(listOf(sharedChannel))
            `when`(sessionMapper.selectByAgentId(10L)).thenReturn(emptyList())
            `when`(sessionMapper.selectByAgentId(11L)).thenReturn(emptyList())

            // When
            val result = createService().listSessionsByCli(5L)

            // Then
            assertEquals(1, result.size)
            assertEquals("shared-session", result[0].sessionId)
        }

        @Test
        @DisplayName("listSessionsByCli - 无关联Agent返回空列表")
        fun `listSessionsByCli should return empty list when no agents bound`() {
            `when`(cliBindingMapper.selectByCliId(5L)).thenReturn(emptyList())

            assertTrue(createService().listSessionsByCli(5L).isEmpty())
        }
    }
}
