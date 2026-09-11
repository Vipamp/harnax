package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.McpServerService
import com.agnetix.harnax.admin.service.ModelProviderService
import com.agnetix.harnax.admin.service.ModelService
import com.agnetix.harnax.admin.service.SkillService
import com.agnetix.harnax.admin.util.JwtUtil
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.AgentMcpBinding
import com.agnetix.harnax.entity.AgentSkillBinding
import com.agnetix.harnax.entity.McpServer
import com.agnetix.harnax.entity.Model
import com.agnetix.harnax.entity.ModelProvider
import com.agnetix.harnax.entity.Skill
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.AgentMcpBindingMapper
import com.agnetix.harnax.mapper.AgentSkillBindingMapper
import com.agnetix.harnax.mapper.MpSessionMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * MpAgentService 单元测试
 * 测试移动端Agent列表与详情查询逻辑
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MpAgentServiceTest {

    @Mock
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var mpSessionMapper: MpSessionMapper

    @Mock
    private lateinit var modelService: ModelService

    @Mock
    private lateinit var modelProviderService: ModelProviderService

    @Mock
    private lateinit var mcpServerService: McpServerService

    @Mock
    private lateinit var skillService: SkillService

    @Mock
    private lateinit var mcpBindingMapper: AgentMcpBindingMapper

    @Mock
    private lateinit var skillBindingMapper: AgentSkillBindingMapper

    @Mock
    private lateinit var jwtUtil: JwtUtil

    @InjectMocks
    private lateinit var mpAgentService: MpAgentService

    private lateinit var testAgent: Agent

    @BeforeEach
    fun setUp() {
        testAgent = Agent().apply {
            id = 10L
            name = "客服Agent"
            description = "智能客服"
            modelId = 100L
            status = 1
            active = 1
            tenantId = 1L
        }

        // 模拟已登录的请求上下文, 供 UserContextUtil.getCurrentUsername 使用
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer valid-token")
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        `when`(jwtUtil.validateToken("valid-token")).thenReturn(true)
        `when`(jwtUtil.getUsernameFromToken("valid-token")).thenReturn("mobileuser")
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
    }

    @Nested
    @DisplayName("查询Agent列表测试")
    inner class ListAgentsTests {

        @Test
        @DisplayName("listAgents - 返回启用Agent及模型名和会话数")
        fun `listAgents should return active agents with model name and session count`() {
            // Given
            `when`(agentMapper.selectAgentList(null, 1, "mobileuser", 1L)).thenReturn(listOf(testAgent))
            `when`(modelService.getModel(100L)).thenReturn(
                Model().apply {
                    id = 100L
                    modelName = "gpt-4o"
                },
            )
            `when`(mpSessionMapper.countByUserIdAndAgentId(1L, 10L)).thenReturn(3)

            // When
            val result = mpAgentService.listAgents(1L, 1L)

            // Then
            assertEquals(1, result.size)
            assertEquals(10L, result[0].id)
            assertEquals("客服Agent", result[0].name)
            assertEquals("智能客服", result[0].description)
            assertEquals("gpt-4o", result[0].modelName)
            assertEquals(1, result[0].status)
            assertEquals(3, result[0].sessionCount)
        }

        @Test
        @DisplayName("listAgents - 过滤非active的Agent")
        fun `listAgents should filter out inactive agents`() {
            // Given
            val inactiveAgent = Agent().apply {
                id = 11L
                name = "已删除Agent"
                active = 0
            }
            `when`(agentMapper.selectAgentList(null, 1, "mobileuser", 1L)).thenReturn(listOf(testAgent, inactiveAgent))
            `when`(modelService.getModel(anyLong())).thenReturn(null)
            `when`(mpSessionMapper.countByUserIdAndAgentId(anyLong(), anyLong())).thenReturn(0)

            // When
            val result = mpAgentService.listAgents(1L, 1L)

            // Then
            assertEquals(1, result.size)
            assertEquals(10L, result[0].id)
        }

        @Test
        @DisplayName("listAgents - 模型不存在时模型名为空字符串")
        fun `listAgents should use empty model name when model not found`() {
            // Given
            `when`(agentMapper.selectAgentList(null, 1, "mobileuser", 1L)).thenReturn(listOf(testAgent))
            `when`(modelService.getModel(100L)).thenReturn(null)
            `when`(mpSessionMapper.countByUserIdAndAgentId(1L, 10L)).thenReturn(0)

            // When
            val result = mpAgentService.listAgents(1L, 1L)

            // Then
            assertEquals("", result[0].modelName)
        }

        @Test
        @DisplayName("listAgents - 无Agent返回空列表")
        fun `listAgents should return empty list when no agents`() {
            `when`(agentMapper.selectAgentList(null, 1, "mobileuser", 1L)).thenReturn(emptyList())

            assertTrue(mpAgentService.listAgents(1L, 1L).isEmpty())
        }

        @Test
        @DisplayName("listAgents - 未登录抛出异常")
        fun `listAgents should throw when not logged in`() {
            // Given: 无请求上下文
            RequestContextHolder.resetRequestAttributes()

            // When & Then
            assertThrows<RuntimeException> {
                mpAgentService.listAgents(1L, 1L)
            }
        }
    }

    @Nested
    @DisplayName("查询Agent详情测试")
    inner class GetAgentDetailTests {

        @Test
        @DisplayName("getAgentDetail - 返回详情含模型、供应商、MCP和技能列表")
        fun `getAgentDetail should return detail with model provider mcp and skills`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent)
            `when`(modelService.getModel(100L)).thenReturn(
                Model().apply {
                    id = 100L
                    modelName = "gpt-4o"
                    providerId = 200L
                },
            )
            `when`(modelProviderService.getModelProvider(200L)).thenReturn(
                ModelProvider().apply {
                    id = 200L
                    name = "OpenAI"
                },
            )
            `when`(mcpBindingMapper.selectByAgentId(10L)).thenReturn(
                listOf(
                    AgentMcpBinding().apply {
                        agentId = 10L
                        mcpId = 300L
                    },
                ),
            )
            `when`(mcpServerService.getMcpServer(300L)).thenReturn(
                McpServer().apply {
                    id = 300L
                    name = "search-mcp"
                    description = "搜索工具"
                },
            )
            `when`(skillBindingMapper.selectByAgentId(10L)).thenReturn(
                listOf(
                    AgentSkillBinding().apply {
                        agentId = 10L
                        skillId = 400L
                    },
                ),
            )
            `when`(skillService.getSkill(400L)).thenReturn(
                Skill().apply {
                    id = 400L
                    name = "translate"
                    description = "翻译技能"
                },
            )

            // When
            val detail = mpAgentService.getAgentDetail(10L, 1L)

            // Then
            assertEquals(10L, detail.id)
            assertEquals("客服Agent", detail.name)
            assertEquals("gpt-4o", detail.modelName)
            assertEquals("OpenAI", detail.modelProvider)
            assertEquals(1, detail.mcpList.size)
            assertEquals("search-mcp", detail.mcpList[0].name)
            assertEquals(1, detail.skillList.size)
            assertEquals("translate", detail.skillList[0].name)
            assertFalse(detail.enableThink)
            assertFalse(detail.enableSearch)
            assertFalse(detail.enablePlan)
        }

        @Test
        @DisplayName("getAgentDetail - Agent不存在抛出异常")
        fun `getAgentDetail should throw when agent not found`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(null)

            // When & Then
            val ex = assertThrows<BizException> {
                mpAgentService.getAgentDetail(10L, 1L)
            }
            assertEquals("Agent not found", ex.message)
        }

        @Test
        @DisplayName("getAgentDetail - 跨租户Agent按不存在处理")
        fun `getAgentDetail should treat cross-tenant agent as not found`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent.apply { tenantId = 9L })

            // When & Then
            val ex = assertThrows<BizException> {
                mpAgentService.getAgentDetail(10L, 1L)
            }
            assertEquals("Agent not found", ex.message)
            org.mockito.Mockito.verify(modelService, org.mockito.Mockito.never()).getModel(anyLong())
        }

        @Test
        @DisplayName("getAgentDetail - Agent已删除抛出异常")
        fun `getAgentDetail should throw when agent is inactive`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent.apply { active = 0 })

            // When & Then
            val ex = assertThrows<BizException> {
                mpAgentService.getAgentDetail(10L, 1L)
            }
            assertEquals("Agent is not available", ex.message)
        }

        @Test
        @DisplayName("getAgentDetail - Agent被禁用抛出异常")
        fun `getAgentDetail should throw when agent is disabled`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent.apply { status = 0 })

            // When & Then
            val ex = assertThrows<BizException> {
                mpAgentService.getAgentDetail(10L, 1L)
            }
            assertEquals("Agent is not available", ex.message)
        }

        @Test
        @DisplayName("getAgentDetail - 模型不存在时模型和供应商为空字符串")
        fun `getAgentDetail should use empty names when model not found`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent)
            `when`(modelService.getModel(100L)).thenReturn(null)
            `when`(mcpBindingMapper.selectByAgentId(10L)).thenReturn(emptyList())
            `when`(skillBindingMapper.selectByAgentId(10L)).thenReturn(emptyList())

            // When
            val detail = mpAgentService.getAgentDetail(10L, 1L)

            // Then
            assertEquals("", detail.modelName)
            assertEquals("", detail.modelProvider)
            // 未查询模型供应商
            org.mockito.Mockito.verify(modelProviderService, org.mockito.Mockito.never()).getModelProvider(anyLong())
        }

        @Test
        @DisplayName("getAgentDetail - 绑定的MCP和技能不存在时被跳过")
        fun `getAgentDetail should skip missing mcp and skill bindings`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent)
            `when`(modelService.getModel(anyLong())).thenReturn(null)
            `when`(modelProviderService.getModelProvider(anyLong())).thenReturn(null)
            `when`(mcpBindingMapper.selectByAgentId(10L)).thenReturn(
                listOf(
                    AgentMcpBinding().apply {
                        agentId = 10L
                        mcpId = 300L
                    },
                ),
            )
            `when`(mcpServerService.getMcpServer(300L)).thenReturn(null)
            `when`(skillBindingMapper.selectByAgentId(10L)).thenReturn(
                listOf(
                    AgentSkillBinding().apply {
                        agentId = 10L
                        skillId = 400L
                    },
                ),
            )
            `when`(skillService.getSkill(400L)).thenReturn(null)

            // When
            val detail = mpAgentService.getAgentDetail(10L, 1L)

            // Then
            assertTrue(detail.mcpList.isEmpty())
            assertTrue(detail.skillList.isEmpty())
        }

        @Test
        @DisplayName("getAgentDetail - 无绑定时MCP和技能列表为空")
        fun `getAgentDetail should return empty lists when no bindings`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent)
            `when`(modelService.getModel(anyLong())).thenReturn(null)
            `when`(mcpBindingMapper.selectByAgentId(10L)).thenReturn(emptyList())
            `when`(skillBindingMapper.selectByAgentId(10L)).thenReturn(emptyList())

            // When
            val detail = mpAgentService.getAgentDetail(10L, 1L)

            // Then
            assertTrue(detail.mcpList.isEmpty())
            assertTrue(detail.skillList.isEmpty())
        }
    }
}
