package com.agnetix.harnax.admin.controller

import com.agnetix.harnax.admin.dto.Page
import com.agnetix.harnax.admin.dto.SessionChatUpdateRequest
import com.agnetix.harnax.admin.dto.SessionCreateRequest
import com.agnetix.harnax.admin.dto.SessionResponse
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.service.SessionService
import com.agnetix.harnax.entity.Session
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
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import java.time.LocalDateTime

/**
 * SessionController 单元测试
 * 直接实例化 Controller + Mock Service，断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionControllerTest {

    @Mock
    private lateinit var sessionService: SessionService

    @InjectMocks
    private lateinit var controller: SessionController

    private lateinit var testSession: Session
    private lateinit var testResponse: SessionResponse

    @BeforeEach
    fun setUp() {
        testSession = Session().apply {
            id = 1L
            tenantId = 1L
            title = "My Session"
            sessionDescription = "Test session"
            sessionId = "web-abc123"
            agentId = 100L
            name = "assistant"
            modelId = 5L
            enableThink = 0
            enableSearch = 0
            enablePlan = 0
            permissionMode = "DEFAULT"
            status = 1
            creator = "admin"
            active = 1
            createTime = LocalDateTime.now()
            updateTime = LocalDateTime.now()
        }

        testResponse = SessionResponse(
            id = 1L,
            title = "My Session",
            sessionDescription = "Test session",
            sessionId = "web-abc123",
            agentId = 100L,
            name = "assistant",
            modelId = 5L,
            permissionMode = "DEFAULT",
            status = 1,
        )
    }

    @Nested
    @DisplayName("GET /api/admin/sessions/page")
    inner class PageEndpoint {

        @Test
        @DisplayName("pageSession - 返回分页结果")
        fun `pageSession should return paginated results`() {
            val page = Page<Session>(total = 1L, pageNum = 1L, pageSize = 10L, records = listOf(testSession))
            `when`(sessionService.page(null, null, 1, 10)).thenReturn(page)
            `when`(sessionService.convertToResponse(testSession)).thenReturn(testResponse)

            val result = controller.pageSession(1, 10, null, null)

            assertTrue(result.isSuccess())
            assertEquals(1L, result.data?.total)
            assertEquals("My Session", result.data?.records?.get(0)?.title)
        }

        @Test
        @DisplayName("pageSession - 传递过滤条件")
        fun `pageSession should pass filters correctly`() {
            val page = Page<Session>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(sessionService.page("keyword", 1, 1, 10)).thenReturn(page)

            val result = controller.pageSession(1, 10, "keyword", 1)

            assertTrue(result.isSuccess())
            verify(sessionService).page("keyword", 1, 1, 10)
        }

        @Test
        @DisplayName("pageSession - pageNum/pageSize 为 null 时使用默认值")
        fun `pageSession should use default paging when null`() {
            val page = Page<Session>(total = 0L, pageNum = 1L, pageSize = 10L, records = emptyList())
            `when`(sessionService.page(null, null, 1, 10)).thenReturn(page)

            val result = controller.pageSession(null, null, null, null)

            assertTrue(result.isSuccess())
            verify(sessionService).page(null, null, 1, 10)
        }

        @Test
        @DisplayName("pageSession - service 抛异常返回 error")
        fun `pageSession should return error when service throws`() {
            `when`(sessionService.page(null, null, 1, 10)).thenThrow(RuntimeException("DB error"))

            val result = controller.pageSession(1, 10, null, null)

            assertFalse(result.isSuccess())
            assertEquals(500, result.code)
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/sessions/{id}")
    inner class GetByIdEndpoint {

        @Test
        @DisplayName("getSession - 返回会话详情")
        fun `getSession should return session details`() {
            `when`(sessionService.getSession(1L)).thenReturn(testSession)
            `when`(sessionService.convertToResponse(testSession)).thenReturn(testResponse)

            val result = controller.getSession(1L)

            assertTrue(result.isSuccess())
            assertEquals("My Session", result.data?.title)
            assertEquals(100L, result.data?.agentId)
        }

        @Test
        @DisplayName("getSession - 不存在时 data 为 null")
        fun `getSession should return null data when not found`() {
            `when`(sessionService.getSession(999L)).thenReturn(null)

            val result = controller.getSession(999L)

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getSession - service 抛异常返回 error")
        fun `getSession should return error when service throws`() {
            `when`(sessionService.getSession(1L)).thenThrow(RuntimeException("DB error"))

            val result = controller.getSession(1L)

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/sessions/check-title")
    inner class CheckTitleEndpoint {

        @Test
        @DisplayName("checkSessionTitle - 已存在时返回 true")
        fun `checkSessionTitle should return true when exists`() {
            `when`(sessionService.existsByTitle("My Session")).thenReturn(true)

            val result = controller.checkSessionTitle("My Session")

            assertTrue(result.isSuccess())
            assertEquals(true, result.data)
        }

        @Test
        @DisplayName("checkSessionTitle - 不存在时返回 false")
        fun `checkSessionTitle should return false when not exists`() {
            `when`(sessionService.existsByTitle("New Session")).thenReturn(false)

            val result = controller.checkSessionTitle("New Session")

            assertTrue(result.isSuccess())
            assertEquals(false, result.data)
        }

        @Test
        @DisplayName("checkSessionTitle - service 抛异常返回 error")
        fun `checkSessionTitle should return error when service throws`() {
            `when`(sessionService.existsByTitle("My Session")).thenThrow(RuntimeException("DB error"))

            val result = controller.checkSessionTitle("My Session")

            assertFalse(result.isSuccess())
            assertEquals("DB error", result.message)
        }
    }

    @Nested
    @DisplayName("POST /api/admin/sessions")
    inner class CreateEndpoint {

        @Test
        @DisplayName("createSession - 创建成功")
        fun `createSession should return success`() {
            val request = SessionCreateRequest(title = "New Session", agentId = 100L)
            `when`(sessionService.createSession(any())).thenReturn(true)

            val result = controller.createSession(request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("createSession - service 返回 false 时返回 error")
        fun `createSession should return error when service returns false`() {
            val request = SessionCreateRequest(title = "New Session", agentId = 100L)
            `when`(sessionService.createSession(any())).thenReturn(false)

            val result = controller.createSession(request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to create session", result.message)
        }

        @Test
        @DisplayName("createSession - Agent 不存在时返回 error")
        fun `createSession should return error when agent not found`() {
            val request = SessionCreateRequest(title = "New Session", agentId = 999L)
            `when`(sessionService.createSession(any())).thenThrow(BizException("Agent not found"))

            val result = controller.createSession(request)

            assertFalse(result.isSuccess())
            assertEquals("Agent not found", result.message)
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/sessions/update/{id}")
    inner class UpdateEndpoint {

        @Test
        @DisplayName("updateSession - 更新成功")
        fun `updateSession should return success`() {
            val request = SessionCreateRequest(title = "Updated Session", agentId = 100L)
            `when`(sessionService.updateSession(any(), any())).thenReturn(true)

            val result = controller.updateSession(1L, request)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("updateSession - service 返回 false 时返回 error")
        fun `updateSession should return error when service returns false`() {
            val request = SessionCreateRequest(title = "Updated Session", agentId = 100L)
            `when`(sessionService.updateSession(any(), any())).thenReturn(false)

            val result = controller.updateSession(1L, request)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update session", result.message)
        }

        @Test
        @DisplayName("updateSession - 会话不存在时返回 error")
        fun `updateSession should return error when not found`() {
            val request = SessionCreateRequest(title = "Updated Session", agentId = 100L)
            `when`(sessionService.updateSession(any(), any())).thenThrow(BizException("Session not found"))

            val result = controller.updateSession(999L, request)

            assertFalse(result.isSuccess())
            assertEquals("Session not found", result.message)
        }
    }

    @Nested
    @DisplayName("GET /api/admin/sessions/{sessionId}/config")
    inner class GetConfigEndpoint {

        @Test
        @DisplayName("getSessionConfig - 返回会话配置")
        fun `getSessionConfig should return session config`() {
            `when`(sessionService.getSessionChatConfig("web-abc123")).thenReturn(testSession)
            `when`(sessionService.convertToResponse(testSession)).thenReturn(testResponse)

            val result = controller.getSessionConfig("web-abc123")

            assertTrue(result.isSuccess())
            assertEquals("web-abc123", result.data?.sessionId)
            assertEquals("DEFAULT", result.data?.permissionMode)
        }

        @Test
        @DisplayName("getSessionConfig - 会话不存在时 data 为 null")
        fun `getSessionConfig should return null data when not found`() {
            `when`(sessionService.getSessionChatConfig("web-notfound")).thenReturn(null)

            val result = controller.getSessionConfig("web-notfound")

            assertTrue(result.isSuccess())
            assertNull(result.data)
        }

        @Test
        @DisplayName("getSessionConfig - service 抛异常返回 error")
        fun `getSessionConfig should return error when service throws`() {
            `when`(sessionService.getSessionChatConfig("web-abc123")).thenThrow(RuntimeException("DB error"))

            val result = controller.getSessionConfig("web-abc123")

            assertFalse(result.isSuccess())
            // 该 endpoint 使用 e.toString() 作为消息
            assertTrue(result.message.contains("DB error"))
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/sessions/{sessionId}/config")
    inner class UpdateConfigEndpoint {

        @Test
        @DisplayName("updateSessionConfig - 更新配置成功")
        fun `updateSessionConfig should return success`() {
            val request = SessionChatUpdateRequest(enableThink = true, enableSearch = false)

            val result = controller.updateSessionConfig("web-abc123", request)

            assertTrue(result.isSuccess())
            verify(sessionService).updateSessionChatConfig(eq("web-abc123"), any())
        }

        @Test
        @DisplayName("updateSessionConfig - service 抛异常返回 error")
        fun `updateSessionConfig should return error when service throws`() {
            val request = SessionChatUpdateRequest(enableThink = true)
            `when`(sessionService.updateSessionChatConfig(any(), any()))
                .thenThrow(RuntimeException("Session not found"))

            val result = controller.updateSessionConfig("web-notfound", request)

            assertFalse(result.isSuccess())
            assertTrue(result.message.contains("Session not found"))
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/sessions/toggle/{id}")
    inner class ToggleEndpoint {

        @Test
        @DisplayName("toggleSession - 切换状态成功")
        fun `toggleSession should return success`() {
            `when`(sessionService.toggleSessionStatus(1L, 0)).thenReturn(true)

            val result = controller.toggleSession(1L, 0)

            assertTrue(result.isSuccess())
            verify(sessionService).toggleSessionStatus(1L, 0)
        }

        @Test
        @DisplayName("toggleSession - service 返回 false 时返回 error")
        fun `toggleSession should return error when service returns false`() {
            `when`(sessionService.toggleSessionStatus(1L, 1)).thenReturn(false)

            val result = controller.toggleSession(1L, 1)

            assertFalse(result.isSuccess())
            assertEquals("Failed to update session", result.message)
        }

        @Test
        @DisplayName("toggleSession - service 抛异常返回 error")
        fun `toggleSession should return error when service throws`() {
            `when`(sessionService.toggleSessionStatus(999L, 1)).thenThrow(BizException("Session not found"))

            val result = controller.toggleSession(999L, 1)

            assertFalse(result.isSuccess())
            assertEquals("Session not found", result.message)
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/sessions/{id}")
    inner class DeleteEndpoint {

        @Test
        @DisplayName("deleteSession - 删除成功")
        fun `deleteSession should return success`() {
            `when`(sessionService.deleteSession(1L)).thenReturn(true)

            val result = controller.deleteSession(1L)

            assertTrue(result.isSuccess())
        }

        @Test
        @DisplayName("deleteSession - service 返回 false 时返回 error")
        fun `deleteSession should return error when service returns false`() {
            `when`(sessionService.deleteSession(1L)).thenReturn(false)

            val result = controller.deleteSession(1L)

            assertFalse(result.isSuccess())
            assertEquals("Failed to delete session", result.message)
        }

        @Test
        @DisplayName("deleteSession - 会话不存在时返回 error")
        fun `deleteSession should return error when not found`() {
            `when`(sessionService.deleteSession(999L)).thenThrow(BizException("Session not found"))

            val result = controller.deleteSession(999L)

            assertFalse(result.isSuccess())
            assertEquals("Session not found", result.message)
        }
    }
}
