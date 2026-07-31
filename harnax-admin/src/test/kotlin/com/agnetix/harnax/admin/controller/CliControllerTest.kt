package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.CliCreateRequest
import com.agnetix.harnax.admin.dto.CliResponse
import com.agnetix.harnax.admin.dto.CliUpdateRequest
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.CliService
import com.agnetix.harnax.admin.service.impl.AgentSessionRefreshService
import com.agnetix.harnax.admin.service.impl.RelatedAgentInfo
import com.agnetix.harnax.admin.service.impl.RelatedSessionInfo
import com.agnetix.harnax.entity.Cli
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
 * CliController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CliControllerTest {

    @Mock
    private lateinit var cliService: CliService

    @Mock
    private lateinit var agentSessionRefreshService: AgentSessionRefreshService

    @InjectMocks
    private lateinit var controller: CliController

    private lateinit var testCli: Cli
    private lateinit var testResponse: CliResponse

    @BeforeEach
    fun setUp() {
        testCli = Cli().apply {
            id = 1L
            tenantId = 1L
            name = "kubectl"
            description = "Kubernetes CLI"
            version = "1.30.0"
            installScript = "RUN curl -LO kubectl && install kubectl"
            checkCommand = "kubectl version --client"
            status = 1
            isPublic = 0
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        testResponse = CliResponse(
            id = 1L,
            name = "kubectl",
            description = "Kubernetes CLI",
            version = "1.30.0",
            status = 1,
        )
    }

    @Nested
    @DisplayName("GET /api/admin/clis/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("pageCli - 返回分页结果")
        fun `pageCli should return paginated results`() {
            val page = Page(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testCli))
            `when`(cliService.page(null, null, 1, 10)).thenReturn(page)
            `when`(cliService.convertToResponse(testCli)).thenReturn(testResponse)

            val result = controller.pageCli(1, 10, null, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("kubectl", result.data?.records?.get(0)?.name)
        }

        @Test
        @DisplayName("pageCli - pageNum/pageSize 为 null 时使用默认值 1/10")
        fun `pageCli should use default pagination when null`() {
            val page = Page<Cli>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(cliService.page(null, null, 1, 10)).thenReturn(page)

            val result = controller.pageCli(null, null, null, null)

            assertTrue(result.isSuccess())
            verify(cliService).page(null, null, 1, 10)
        }

        @Test
        @DisplayName("pageCli - 透传筛选条件")
        fun `pageCli should pass filters correctly`() {
            val page = Page<Cli>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(cliService.page("kube", 1, 2, 20)).thenReturn(page)

            val result = controller.pageCli(2, 20, "kube", 1)

            assertTrue(result.isSuccess())
            verify(cliService).page("kube", 1, 2, 20)
        }

        @Test
        @DisplayName("pageCli - service 抛异常时返回错误")
        fun `pageCli should return error on service exception`() {
            `when`(cliService.page(null, null, 1, 10)).thenThrow(RuntimeException("DB error"))

            val result = controller.pageCli(1, 10, null, null)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/clis/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getCli - 存在时返回详情")
        fun `getCli should return cli when found`() {
            `when`(cliService.getCli(1L)).thenReturn(testCli)
            `when`(cliService.convertToResponse(testCli)).thenReturn(testResponse)

            val result = controller.getCli(1L)

            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals("kubectl", result.data?.name)
            assertEquals("1.30.0", result.data?.version)
        }

        @Test
        @DisplayName("getCli - 不存在时 data 为 null")
        fun `getCli should return null data when not found`() {
            `when`(cliService.getCli(999L)).thenReturn(null)

            val result = controller.getCli(999L)

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getCli - service 抛异常时返回错误")
        fun `getCli should return error on service exception`() {
            `when`(cliService.getCli(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.getCli(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/clis")
    inner class CreateEndpoint {

        private val request = CliCreateRequest(
            name = "kubectl",
            installScript = "RUN curl -LO kubectl && install kubectl",
            version = "1.30.0",
        )

        @Test
        @DisplayName("createCli - 创建成功")
        fun `createCli should return success`() {
            `when`(cliService.createCli(any())).thenReturn(true)

            val result = controller.createCli(request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("createCli - 创建失败时返回错误")
        fun `createCli should return error when service returns false`() {
            `when`(cliService.createCli(any())).thenReturn(false)

            val result = controller.createCli(request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to create CLI", result.message)
        }

        @Test
        @DisplayName("createCli - service 抛异常时返回错误")
        fun `createCli should return error on service exception`() {
            `when`(cliService.createCli(any())).thenThrow(BizException("CLI name already exists"))

            val result = controller.createCli(request)

            assertFalse(result.isSuccess())
            assertEquals("CLI name already exists", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/clis/update/{id}")
    inner class UpdateEndpoint {

        private val request = CliUpdateRequest(version = "1.31.0")

        @Test
        @DisplayName("updateCli - 更新成功")
        fun `updateCli should return success`() {
            `when`(cliService.updateCli(any(), any())).thenReturn(true)

            val result = controller.updateCli(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("updateCli - 更新失败时返回错误")
        fun `updateCli should return error when service returns false`() {
            `when`(cliService.updateCli(any(), any())).thenReturn(false)

            val result = controller.updateCli(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update CLI", result.message)
        }

        @Test
        @DisplayName("updateCli - service 抛异常时返回错误")
        fun `updateCli should return error on service exception`() {
            `when`(cliService.updateCli(any(), any())).thenThrow(BizException("CLI not found"))

            val result = controller.updateCli(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("CLI not found", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/clis/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggleCli - 切换成功")
        fun `toggleCli should return success`() {
            `when`(cliService.toggleCliStatus(1L, 1)).thenReturn(true)

            val result = controller.toggleCli(1L, 1)

            assertTrue(result.isSuccess())
            verify(cliService).toggleCliStatus(1L, 1)
        }

        @Test
        @DisplayName("toggleCli - 切换失败时返回错误")
        fun `toggleCli should return error when service returns false`() {
            `when`(cliService.toggleCliStatus(1L, 0)).thenReturn(false)

            val result = controller.toggleCli(1L, 0)

            assertFalse(result.isSuccess())
            assertEquals("Failed to toggle CLI status", result.message)
        }

        @Test
        @DisplayName("toggleCli - service 抛异常时返回错误")
        fun `toggleCli should return error on service exception`() {
            `when`(cliService.toggleCliStatus(999L, 1)).thenThrow(RuntimeException("DB error"))

            val result = controller.toggleCli(999L, 1)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/clis/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("deleteCli - 删除成功")
        fun `deleteCli should return success`() {
            `when`(cliService.deleteCli(1L)).thenReturn(true)

            val result = controller.deleteCli(1L)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("deleteCli - 删除失败时返回错误")
        fun `deleteCli should return error when service returns false`() {
            `when`(cliService.deleteCli(999L)).thenReturn(false)

            val result = controller.deleteCli(999L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete CLI", result.message)
        }

        @Test
        @DisplayName("deleteCli - service 抛异常时返回错误")
        fun `deleteCli should return error on service exception`() {
            `when`(cliService.deleteCli(1L)).thenThrow(BizException("CLI is in use"))

            val result = controller.deleteCli(1L)

            assertFalse(result.isSuccess())
            assertEquals("CLI is in use", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/clis/{id}/related-agents")
    inner class RelatedAgentsEndpoint {

        @Test
        @DisplayName("relatedAgents - 返回绑定的 Agent 列表")
        fun `relatedAgents should return agent list`() {
            val agents = listOf(
                RelatedAgentInfo(agentId = 100L, agentName = "Agent A", status = 1),
                RelatedAgentInfo(agentId = 200L, agentName = "Agent B", status = 0),
            )
            `when`(agentSessionRefreshService.listAgentsByCli(1L)).thenReturn(agents)

            val result = controller.relatedAgents(1L)

            assertTrue(result.isSuccess())
            assertEquals(2, result.data?.size)
            assertEquals(100L, result.data?.get(0)?.agentId)
            assertEquals("Agent A", result.data?.get(0)?.agentName)
        }

        @Test
        @DisplayName("relatedAgents - 无绑定时返回空列表")
        fun `relatedAgents should return empty list when none bound`() {
            `when`(agentSessionRefreshService.listAgentsByCli(1L)).thenReturn(emptyList())

            val result = controller.relatedAgents(1L)

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("relatedAgents - service 抛异常时返回错误")
        fun `relatedAgents should return error on service exception`() {
            `when`(agentSessionRefreshService.listAgentsByCli(1L))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.relatedAgents(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/clis/{id}/related-sessions")
    inner class RelatedSessionsEndpoint {

        @Test
        @DisplayName("relatedSessions - 返回受影响的会话列表")
        fun `relatedSessions should return session list`() {
            val sessions = listOf(
                RelatedSessionInfo(sessionId = "web-abc", sourceType = "session", sourceName = "Session A", agentName = "Agent A"),
                RelatedSessionInfo(sessionId = "chn-xyz", sourceType = "channel", sourceName = "Channel B", agentName = "Agent B"),
            )
            `when`(agentSessionRefreshService.listSessionsByCli(1L)).thenReturn(sessions)

            val result = controller.relatedSessions(1L)

            assertTrue(result.isSuccess())
            assertEquals(2, result.data?.size)
            assertEquals("web-abc", result.data?.get(0)?.sessionId)
            assertEquals("channel", result.data?.get(1)?.sourceType)
        }

        @Test
        @DisplayName("relatedSessions - 无会话时返回空列表")
        fun `relatedSessions should return empty list when none affected`() {
            `when`(agentSessionRefreshService.listSessionsByCli(1L)).thenReturn(emptyList())

            val result = controller.relatedSessions(1L)

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("relatedSessions - service 抛异常时返回错误")
        fun `relatedSessions should return error on service exception`() {
            `when`(agentSessionRefreshService.listSessionsByCli(1L))
                .thenThrow(RuntimeException("DB error"))

            val result = controller.relatedSessions(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }
}
