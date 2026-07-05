package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentTask
import com.agnetix.harnax.entity.ApiKeyEntity
import com.agnetix.harnax.entity.Channel
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentTaskMapper
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.ModelMapper
import com.agnetix.harnax.mapper.SessionMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
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
    private lateinit var agentTaskMapper: AgentTaskMapper

    @Mock
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var channelMapper: ChannelMapper

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
                mcpList = "[]"
                skillList = ""
            }
            `when`(agentTaskMapper.selectById(1L)).thenReturn(task)
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
        @DisplayName("getAgentTaskSpec - 任务不存在时返回错误")
        fun `getAgentTaskSpec should return error when task not found`() {
            `when`(agentTaskMapper.selectById(999L)).thenReturn(null)

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
            `when`(agentTaskMapper.selectById(1L)).thenReturn(task)
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
            mcpList = "[]"
            skillList = ""
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
            `when`(agentTaskMapper.selectById(42L)).thenReturn(task)
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
}
