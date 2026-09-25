package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.CliResponse
import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.i18n.MessageUtil
import com.agnetix.harnax.admin.service.CliService
import com.agnetix.harnax.admin.service.impl.AgentSessionRefreshService
import com.agnetix.harnax.admin.service.impl.RelatedAgentInfo
import com.agnetix.harnax.admin.service.impl.RelatedSessionInfo
import com.agnetix.harnax.entity.Cli
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * CliController Unit Tests
 *
 * Instantiates the controller with a mocked service and calls the handler methods directly. Only the
 * read routes and the status switch are covered: publishing a package is the registrar's job, so this
 * controller has no create/update/delete handlers left to test.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CliControllerTest {

    @Mock
    private lateinit var cliService: CliService

    @Mock
    private lateinit var agentSessionRefreshService: AgentSessionRefreshService

    @Mock
    private lateinit var messageUtil: MessageUtil

    @InjectMocks
    private lateinit var controller: CliController

    private lateinit var testCli: Cli
    private lateinit var testResponse: CliResponse

    @BeforeEach
    fun setUp() {
        // MessageUtil 桩成回显消息码：断言只看键，不依赖 bundle 文案
        `when`(messageUtil.getMessage(anyString())).thenAnswer { invocation -> invocation.arguments[0] as String }
        testCli = Cli().apply {
            id = 1L
            name = "harnax-cli"
            description = "Harnax command line"
            version = "1.30.0"
            checkCommand = "harnax --version"
            packageDigest = "a".repeat(64)
            status = 1
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }
        testResponse = CliResponse(
            id = 1L,
            name = "harnax-cli",
            description = "Harnax command line",
            version = "1.30.0",
            checkCommand = "harnax --version",
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
            assertEquals("harnax-cli", result.data?.records?.get(0)?.name)
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
            `when`(cliService.page("harnax", 1, 2, 20)).thenReturn(page)

            val result = controller.pageCli(2, 20, "harnax", 1)

            assertTrue(result.isSuccess())
            verify(cliService).page("harnax", 1, 2, 20)
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
            `when`(cliService.convertToDetailResponse(testCli)).thenReturn(testResponse)

            val result = controller.getCli(1L)

            assertTrue(result.isSuccess())
            assertNotNull(result.data)
            assertEquals("harnax-cli", result.data?.name)
            assertEquals("1.30.0", result.data?.version)
            // Only the detail shape carries the manifest columns, so the page shape must not be used here
            verify(cliService).convertToDetailResponse(testCli)
        }

        @Test
        @DisplayName("getCli - 读不到时返回具名 404")
        fun `getCli should report not found with a named 404`() {
            `when`(cliService.getCli(999L)).thenReturn(null)

            val result = controller.getCli(999L)

            assertFalse(result.isSuccess(), "读不到的行不能算成功响应")
            assertEquals(404, result.code)
            assertEquals("error.cli.notfound", result.message)
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
