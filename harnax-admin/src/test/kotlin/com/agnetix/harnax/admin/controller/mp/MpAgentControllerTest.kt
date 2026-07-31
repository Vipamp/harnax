package com.agnetix.harnax.admin.controller.mp

import com.agnetix.harnax.admin.dto.mp.MpAgentDetailResponse
import com.agnetix.harnax.admin.dto.mp.MpAgentResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.mp.MpAgentService
import com.agnetix.harnax.entity.SysUser
import com.agnetix.harnax.mapper.SysUserMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * MpAgentController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MpAgentControllerTest {

    @Mock
    private lateinit var mpAgentService: MpAgentService

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @InjectMocks
    private lateinit var controller: MpAgentController

    private lateinit var testUser: SysUser

    @BeforeEach
    fun setUp() {
        testUser = SysUser().apply {
            id = 1L
            username = "mpuser"
            status = 1
        }
        // 初始化 SecurityUtils 单例,使 SecurityUtils.getCurrentUser() 可用
        SecurityUtils(sysUserMapper).init()
        `when`(sysUserMapper.selectByUsername("mpuser")).thenReturn(testUser)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    private fun loginAs(username: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(username, null, emptyList())
    }

    @Nested
    @DisplayName("GET /api/admin/mp/agents")
    inner class ListAgentsEndpoint {

        @Test
        @DisplayName("listAgents - 已登录时返回 Agent 列表")
        fun `listAgents should return agent list when logged in`() {
            loginAs("mpuser")
            val agents = listOf(
                MpAgentResponse(id = 1L, name = "Agent A", description = "desc A", modelName = "gpt-4", status = 1, sessionCount = 2),
                MpAgentResponse(id = 2L, name = "Agent B", description = "desc B", modelName = "gpt-3.5", status = 1, sessionCount = 0),
            )
            `when`(mpAgentService.listAgents(1L)).thenReturn(agents)

            val result = controller.listAgents()

            assertTrue(result.isSuccess())
            assertEquals(2, result.data?.size)
            assertEquals("Agent A", result.data?.get(0)?.name)
            assertEquals(2, result.data?.get(0)?.sessionCount)
        }

        @Test
        @DisplayName("listAgents - 无可用 Agent 时返回空列表")
        fun `listAgents should return empty list when no agents`() {
            loginAs("mpuser")
            `when`(mpAgentService.listAgents(1L)).thenReturn(emptyList())

            val result = controller.listAgents()

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("listAgents - 未登录时返回错误")
        fun `listAgents should return error when not logged in`() {
            SecurityContextHolder.clearContext()

            val result = controller.listAgents()

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
        }

        @Test
        @DisplayName("listAgents - service 抛异常时异常向上传播")
        fun `listAgents should propagate service exception`() {
            loginAs("mpuser")
            `when`(mpAgentService.listAgents(1L)).thenThrow(RuntimeException("DB error"))

            assertThrows<RuntimeException> {
                controller.listAgents()
            }
        }
    }

    @Nested
    @DisplayName("GET /api/admin/mp/agents/{agentId}")
    inner class GetAgentDetailEndpoint {

        @Test
        @DisplayName("getAgentDetail - 已登录时返回 Agent 详情")
        fun `getAgentDetail should return detail when logged in`() {
            loginAs("mpuser")
            val detail = MpAgentDetailResponse(
                id = 1L,
                name = "Agent A",
                description = "desc A",
                modelName = "gpt-4",
                modelProvider = "OpenAI",
                mcpList = listOf(MpAgentDetailResponse.McpInfo(id = 10L, name = "mcp-a", description = "mcp desc")),
                skillList = listOf(MpAgentDetailResponse.SkillInfo(id = 20L, name = "skill-a", description = "skill desc")),
                enableThink = false,
                enableSearch = false,
                enablePlan = false,
            )
            `when`(mpAgentService.getAgentDetail(1L)).thenReturn(detail)

            val result = controller.getAgentDetail(1L)

            assertTrue(result.isSuccess())
            assertEquals("Agent A", result.data?.name)
            assertEquals("OpenAI", result.data?.modelProvider)
            assertEquals(1, result.data?.mcpList?.size)
            assertEquals(1, result.data?.skillList?.size)
        }

        @Test
        @DisplayName("getAgentDetail - 未登录时返回错误")
        fun `getAgentDetail should return error when not logged in`() {
            SecurityContextHolder.clearContext()

            val result = controller.getAgentDetail(1L)

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
        }

        @Test
        @DisplayName("getAgentDetail - Agent 不存在时异常向上传播")
        fun `getAgentDetail should propagate exception when agent not found`() {
            loginAs("mpuser")
            `when`(mpAgentService.getAgentDetail(999L)).thenThrow(BizException("Agent not found"))

            assertThrows<BizException> {
                controller.getAgentDetail(999L)
            }
        }

        @Test
        @DisplayName("getAgentDetail - Agent 不可用时异常向上传播")
        fun `getAgentDetail should propagate exception when agent unavailable`() {
            loginAs("mpuser")
            `when`(mpAgentService.getAgentDetail(2L)).thenThrow(BizException("Agent is not available"))

            assertThrows<BizException> {
                controller.getAgentDetail(2L)
            }
        }
    }
}
