package com.agnetix.harnax.admin.controller.mp

import com.agnetix.harnax.admin.dto.mp.MpCreateSessionRequest
import com.agnetix.harnax.admin.dto.mp.MpSessionResponse
import com.agnetix.harnax.admin.dto.mp.MpUpdateSessionRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.mp.MpSessionService
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
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.LocalDateTime

/**
 * MpSessionController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MpSessionControllerTest {

    @Mock
    private lateinit var mpSessionService: MpSessionService

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @InjectMocks
    private lateinit var controller: MpSessionController

    private lateinit var testUser: SysUser
    private lateinit var testSession: MpSessionResponse

    @BeforeEach
    fun setUp() {
        testUser = SysUser().apply {
            id = 1L
            tenantId = 5L
            username = "mpuser"
            status = 1
        }
        testSession = MpSessionResponse(
            id = 100L,
            sessionName = "My Chat",
            routerSessionId = "mp-uuid-123",
            agentId = 10L,
            agentName = "Agent A",
            status = 1,
            messageCount = 3,
            createTime = LocalDateTime.now(),
            updateTime = LocalDateTime.now(),
        )
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
    @DisplayName("GET /api/admin/mp/sessions")
    inner class ListSessionsEndpoint {

        @Test
        @DisplayName("listSessions - 已登录时返回会话列表")
        fun `listSessions should return session list when logged in`() {
            loginAs("mpuser")
            `when`(mpSessionService.listSessions(1L)).thenReturn(listOf(testSession))

            val result = controller.listSessions()

            assertTrue(result.isSuccess())
            assertEquals(1, result.data?.size)
            assertEquals("My Chat", result.data?.get(0)?.sessionName)
            assertEquals("mp-uuid-123", result.data?.get(0)?.routerSessionId)
        }

        @Test
        @DisplayName("listSessions - 无会话时返回空列表")
        fun `listSessions should return empty list when no sessions`() {
            loginAs("mpuser")
            `when`(mpSessionService.listSessions(1L)).thenReturn(emptyList())

            val result = controller.listSessions()

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("listSessions - 未登录时返回错误")
        fun `listSessions should return error when not logged in`() {
            SecurityContextHolder.clearContext()

            val result = controller.listSessions()

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
            verify(mpSessionService, never()).listSessions(any())
        }
    }

    @Nested
    @DisplayName("POST /api/admin/mp/sessions")
    inner class CreateSessionEndpoint {

        private val request = MpCreateSessionRequest(sessionName = "New Chat", agentId = 10L)

        @Test
        @DisplayName("createSession - 已登录时创建成功")
        fun `createSession should return created session when logged in`() {
            loginAs("mpuser")
            `when`(mpSessionService.createSession(eq(1L), eq(5L), any())).thenReturn(testSession)

            val result = controller.createSession(request)

            assertTrue(result.isSuccess())
            assertEquals(100L, result.data?.id)
            assertEquals(10L, result.data?.agentId)
            verify(mpSessionService).createSession(1L, 5L, request)
        }

        @Test
        @DisplayName("createSession - 未登录时返回错误")
        fun `createSession should return error when not logged in`() {
            SecurityContextHolder.clearContext()

            val result = controller.createSession(request)

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
            verify(mpSessionService, never()).createSession(any(), anyOrNull(), any())
        }

        @Test
        @DisplayName("createSession - Agent 不存在时异常向上传播")
        fun `createSession should propagate exception when agent not found`() {
            loginAs("mpuser")
            `when`(mpSessionService.createSession(eq(1L), eq(5L), any()))
                .thenThrow(BizException("Agent not found"))

            assertThrows<BizException> {
                controller.createSession(request)
            }
        }

        @Test
        @DisplayName("createSession - Agent 不可用时异常向上传播")
        fun `createSession should propagate exception when agent unavailable`() {
            loginAs("mpuser")
            `when`(mpSessionService.createSession(eq(1L), eq(5L), any()))
                .thenThrow(BizException("Agent is not available"))

            assertThrows<BizException> {
                controller.createSession(request)
            }
        }
    }

    @Nested
    @DisplayName("PUT /api/admin/mp/sessions/{id}")
    inner class UpdateSessionEndpoint {

        private val request = MpUpdateSessionRequest(sessionName = "Renamed Chat")

        @Test
        @DisplayName("updateSession - 已登录时更新成功")
        fun `updateSession should return updated session when logged in`() {
            loginAs("mpuser")
            val updated = testSession.copy(sessionName = "Renamed Chat")
            `when`(mpSessionService.updateSession(eq(1L), eq(100L), any())).thenReturn(updated)

            val result = controller.updateSession(100L, request)

            assertTrue(result.isSuccess())
            assertEquals("Renamed Chat", result.data?.sessionName)
            verify(mpSessionService).updateSession(1L, 100L, request)
        }

        @Test
        @DisplayName("updateSession - 未登录时返回错误")
        fun `updateSession should return error when not logged in`() {
            SecurityContextHolder.clearContext()

            val result = controller.updateSession(100L, request)

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
            verify(mpSessionService, never()).updateSession(any(), any(), any())
        }

        @Test
        @DisplayName("updateSession - 会话不属于当前用户时异常向上传播")
        fun `updateSession should propagate exception when session not owned`() {
            loginAs("mpuser")
            `when`(mpSessionService.updateSession(eq(1L), eq(999L), any()))
                .thenThrow(BizException("Session not found or access denied"))

            assertThrows<BizException> {
                controller.updateSession(999L, request)
            }
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/mp/sessions/{id}")
    inner class DeleteSessionEndpoint {

        @Test
        @DisplayName("deleteSession - 已登录时删除成功")
        fun `deleteSession should delete when logged in`() {
            loginAs("mpuser")

            val result = controller.deleteSession(100L)

            assertTrue(result.isSuccess())
            verify(mpSessionService).deleteSession(1L, 100L)
        }

        @Test
        @DisplayName("deleteSession - 未登录时返回错误")
        fun `deleteSession should return error when not logged in`() {
            SecurityContextHolder.clearContext()

            val result = controller.deleteSession(100L)

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
            verify(mpSessionService, never()).deleteSession(any(), any())
        }

        @Test
        @DisplayName("deleteSession - 会话不属于当前用户时异常向上传播")
        fun `deleteSession should propagate exception when session not owned`() {
            loginAs("mpuser")
            doThrow(BizException("Session not found or access denied"))
                .`when`(mpSessionService).deleteSession(1L, 999L)

            assertThrows<BizException> {
                controller.deleteSession(999L)
            }
        }
    }
}
