package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.AgentCreateRequest
import com.agnetix.harnax.admin.dto.AgentResponse
import com.agnetix.harnax.admin.dto.AgentUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.AgentService
import com.agnetix.harnax.admin.service.impl.AgentSessionRefreshService
import com.agnetix.harnax.admin.service.impl.RelatedSessionInfo
import com.agnetix.harnax.admin.service.impl.SessionRefreshResult
import com.agnetix.harnax.entity.Agent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * AgentController 单元测试
 * 直接实例化 Controller + Mock Service，断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentControllerTest {

    @Mock
    private lateinit var agentService: AgentService

    @Mock
    private lateinit var agentSessionRefreshService: AgentSessionRefreshService

    @InjectMocks
    private lateinit var controller: AgentController

    private lateinit var testAgent: Agent
    private lateinit var testResponse: AgentResponse

    @BeforeEach
    fun setUp() {
        testAgent = Agent().apply {
            id = 1L
            tenantId = 1L
            name = "assistant"
            description = "Test agent"
            systemPrompt = "You are a helpful assistant"
            modelId = 100L
            status = 1
            isPublic = 0
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testResponse = AgentResponse(
            id = 1L,
            name = "assistant",
            description = "Test agent",
            systemPrompt = "You are a helpful assistant",
            modelId = 100L,
            status = 1,
        )
    }

    @Nested
    @DisplayName("GET /api/admin/agents/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("pageAgent - 返回分页结果")
        fun `pageAgent should return paginated results`() {
            val page = Page<Agent>(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testAgent))
            `when`(agentService.page(null, null, 1, 10)).thenReturn(page)
            `when`(agentService.convertToResponse(testAgent)).thenReturn(testResponse)

            val result = controller.pageAgent(1, 10, null, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("assistant", result.data?.records?.get(0)?.name)
        }

        @Test
        @DisplayName("pageAgent - pageNum/pageSize 为 null 时使用默认值")
        fun `pageAgent should use default paging when null`() {
            val page = Page<Agent>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(agentService.page(null, null, 1, 10)).thenReturn(page)

            val result = controller.pageAgent(null, null, null, null)

            assertTrue(result.isSuccess())
            verify(agentService).page(null, null, 1, 10)
        }

        @Test
        @DisplayName("pageAgent - 传递过滤条件")
        fun `pageAgent should pass filters correctly`() {
            val page = Page<Agent>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(agentService.page("assistant", 1, 1, 10)).thenReturn(page)

            val result = controller.pageAgent(1, 10, "assistant", 1)

            assertTrue(result.isSuccess())
            verify(agentService).page("assistant", 1, 1, 10)
        }

        @Test
        @DisplayName("pageAgent - service 抛异常返回 error")
        fun `pageAgent should return error when service throws`() {
            `when`(agentService.page(null, null, 1, 10)).thenThrow(RuntimeException("DB error"))

            val result = controller.pageAgent(1, 10, null, null)

            assertFalse(result.isSuccess())
            assertEquals(500, result.code)
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/agents/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getAgent - 返回 Agent 详情")
        fun `getAgent should return agent details`() {
            `when`(agentService.getAgent(1L)).thenReturn(testAgent)
            `when`(agentService.convertToResponse(testAgent)).thenReturn(testResponse)

            val result = controller.getAgent(1L)

            assertTrue(result.isSuccess())
            assertEquals("assistant", result.data?.name)
            assertEquals(100L, result.data?.modelId)
        }

        @Test
        @DisplayName("getAgent - 不存在时 data 为 null")
        fun `getAgent should return null data when not found`() {
            `when`(agentService.getAgent(999L)).thenReturn(null)

            val result = controller.getAgent(999L)

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getAgent - service 抛异常返回 error")
        fun `getAgent should return error when service throws`() {
            `when`(agentService.getAgent(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.getAgent(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/agents")
    inner class CreateEndpoint {

        @Test
        @DisplayName("createAgent - 创建成功")
        fun `createAgent should return success`() {
            val request = AgentCreateRequest(name = "new-agent", modelId = 100L)
            `when`(agentService.createAgent(any())).thenReturn(true)

            val result = controller.createAgent(request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("createAgent - service 返回 false 时返回 error")
        fun `createAgent should return error when service returns false`() {
            val request = AgentCreateRequest(name = "new-agent")
            `when`(agentService.createAgent(any())).thenReturn(false)

            val result = controller.createAgent(request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to create agent", result.message)
        }

        @Test
        @DisplayName("createAgent - 名称重复时返回 error")
        fun `createAgent should return error on duplicate name`() {
            val request = AgentCreateRequest(name = "assistant")
            `when`(agentService.createAgent(any())).thenThrow(BizException("Agent name already exists"))

            val result = controller.createAgent(request)

            assertFalse(result.isSuccess())
            assertEquals("Agent name already exists", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/agents/update/{agentId}")
    inner class UpdateEndpoint {

        @Test
        @DisplayName("updateAgent - 更新成功")
        fun `updateAgent should return success`() {
            val request = AgentUpdateRequest(name = "updated-agent")
            `when`(agentService.updateAgent(any(), any())).thenReturn(true)

            val result = controller.updateAgent(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("updateAgent - service 返回 false 时返回 error")
        fun `updateAgent should return error when service returns false`() {
            val request = AgentUpdateRequest(name = "updated-agent")
            `when`(agentService.updateAgent(any(), any())).thenReturn(false)

            val result = controller.updateAgent(1L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update agent", result.message)
        }

        @Test
        @DisplayName("updateAgent - Agent 不存在时返回 error")
        fun `updateAgent should return error when not found`() {
            val request = AgentUpdateRequest(name = "updated-agent")
            `when`(agentService.updateAgent(any(), any())).thenThrow(BizException("Agent not found"))

            val result = controller.updateAgent(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Agent not found", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/agents/{agentId}/related-sessions")
    inner class RelatedSessionsEndpoint {

        @Test
        @DisplayName("relatedSessions - 返回关联会话列表")
        fun `relatedSessions should return session list`() {
            val sessions = listOf(
                RelatedSessionInfo(sessionId = "web-abc", sourceType = "session", sourceName = "My Session"),
                RelatedSessionInfo(sessionId = "chn-xyz", sourceType = "channel", sourceName = "WeCom Channel"),
            )
            `when`(agentSessionRefreshService.listRelatedSessions(1L)).thenReturn(sessions)

            val result = controller.relatedSessions(1L)

            assertTrue(result.isSuccess())
            assertEquals(2, result.data?.size)
            assertEquals("web-abc", result.data?.get(0)?.sessionId)
        }

        @Test
        @DisplayName("relatedSessions - 无关联会话时返回空列表")
        fun `relatedSessions should return empty list when none`() {
            `when`(agentSessionRefreshService.listRelatedSessions(1L)).thenReturn(emptyList())

            val result = controller.relatedSessions(1L)

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("relatedSessions - service 抛异常返回 error")
        fun `relatedSessions should return error when service throws`() {
            `when`(agentSessionRefreshService.listRelatedSessions(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.relatedSessions(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/agents/refresh-sessions")
    inner class RefreshSessionsEndpoint {

        @Test
        @DisplayName("refreshSessions - 刷新成功返回结果列表")
        fun `refreshSessions should return refresh results`() {
            val results = listOf(
                SessionRefreshResult(sessionId = "web-abc", success = true, error = null),
                SessionRefreshResult(sessionId = "chn-xyz", success = false, error = "timeout"),
            )
            `when`(agentSessionRefreshService.refreshSessions(listOf("web-abc", "chn-xyz"))).thenReturn(results)

            val result = controller.refreshSessions(
                AgentController.RefreshSessionsRequest(sessionIds = listOf("web-abc", "chn-xyz")),
            )

            assertTrue(result.isSuccess())
            assertEquals(2, result.data?.size)
            assertTrue(result.data?.get(0)?.success == true)
            assertEquals("timeout", result.data?.get(1)?.error)
        }

        @Test
        @DisplayName("refreshSessions - 空列表也可正常处理")
        fun `refreshSessions should handle empty session ids`() {
            `when`(agentSessionRefreshService.refreshSessions(emptyList())).thenReturn(emptyList())

            val result = controller.refreshSessions(AgentController.RefreshSessionsRequest())

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("refreshSessions - service 抛异常返回 error")
        fun `refreshSessions should return error when service throws`() {
            `when`(agentSessionRefreshService.refreshSessions(any())).thenThrow(RuntimeException("Redis unavailable"))

            val result = controller.refreshSessions(
                AgentController.RefreshSessionsRequest(sessionIds = listOf("web-abc")),
            )

            assertFalse(result.isSuccess())
            assertEquals("Redis unavailable", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/agents/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggleAgent - 切换状态成功")
        fun `toggleAgent should return success`() {
            `when`(agentService.toggleAgentStatus(1L, 1)).thenReturn(true)

            val result = controller.toggleAgent(1L, 1)

            assertTrue(result.isSuccess())
            verify(agentService).toggleAgentStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleAgent - service 返回 false 时返回 error")
        fun `toggleAgent should return error when service returns false`() {
            `when`(agentService.toggleAgentStatus(1L, 0)).thenReturn(false)

            val result = controller.toggleAgent(1L, 0)

            assertFalse(result.isSuccess())
            assertEquals("Failed to toggle agent status", result.message)
        }

        @Test
        @DisplayName("toggleAgent - service 抛异常返回 error")
        fun `toggleAgent should return error when service throws`() {
            `when`(agentService.toggleAgentStatus(999L, 1)).thenThrow(BizException("Agent not found"))

            val result = controller.toggleAgent(999L, 1)

            assertFalse(result.isSuccess())
            assertEquals("Agent not found", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/agents/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("deleteAgent - 删除成功")
        fun `deleteAgent should return success`() {
            `when`(agentService.deleteAgent(1L)).thenReturn(true)

            val result = controller.deleteAgent(1L)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("deleteAgent - service 返回 false 时返回 error")
        fun `deleteAgent should return error when service returns false`() {
            `when`(agentService.deleteAgent(1L)).thenReturn(false)

            val result = controller.deleteAgent(1L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete agent", result.message)
        }

        @Test
        @DisplayName("deleteAgent - Agent 不存在时返回 error")
        fun `deleteAgent should return error when not found`() {
            `when`(agentService.deleteAgent(999L)).thenThrow(BizException("Agent not found"))

            val result = controller.deleteAgent(999L)

            assertFalse(result.isSuccess())
            assertEquals("Agent not found", result.message)
        }
    }
}
