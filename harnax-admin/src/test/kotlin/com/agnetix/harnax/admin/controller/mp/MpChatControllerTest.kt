package com.agnetix.harnax.admin.controller.mp

import com.agnetix.harnax.admin.dto.mp.MpChatMessageDto
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.admin.security.SecurityUtils
import com.agnetix.harnax.admin.service.mp.MpMessageService
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
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * MpChatController 单元测试
 * 直接实例化 Controller + Mock Service,直调方法断言 ResultVo
 * 注:AI 流式聊天走 router SSE,不经过此 Controller,此处只测消息历史接口
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MpChatControllerTest {

    @Mock
    private lateinit var mpMessageService: MpMessageService

    @Mock
    private lateinit var sysUserMapper: SysUserMapper

    @InjectMocks
    private lateinit var controller: MpChatController

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
    @DisplayName("GET /api/admin/mp/chat/history/{sessionId}")
    inner class GetHistoryEndpoint {

        @Test
        @DisplayName("getHistory - 已登录时返回消息历史")
        fun `getHistory should return messages when logged in`() {
            loginAs("mpuser")
            val messages = listOf(
                MpChatMessageDto(role = "user", content = "Hello"),
                MpChatMessageDto(role = "assistant", content = "Hi there"),
            )
            `when`(mpMessageService.getHistory(1L, 100L)).thenReturn(messages)

            val result = controller.getHistory(100L)

            assertTrue(result.isSuccess())
            assertEquals(2, result.data?.size)
            assertEquals("user", result.data?.get(0)?.role)
            assertEquals("Hi there", result.data?.get(1)?.content)
        }

        @Test
        @DisplayName("getHistory - 无消息时返回空列表")
        fun `getHistory should return empty list when no messages`() {
            loginAs("mpuser")
            `when`(mpMessageService.getHistory(1L, 100L)).thenReturn(emptyList())

            val result = controller.getHistory(100L)

            assertTrue(result.isSuccess())
            assertTrue(result.data?.isEmpty() == true)
        }

        @Test
        @DisplayName("getHistory - 未登录时返回错误")
        fun `getHistory should return error when not logged in`() {
            SecurityContextHolder.clearContext()

            val result = controller.getHistory(100L)

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
            verify(mpMessageService, never()).getHistory(any(), any())
        }

        @Test
        @DisplayName("getHistory - 会话不属于当前用户时异常向上传播")
        fun `getHistory should propagate exception when session not owned`() {
            loginAs("mpuser")
            `when`(mpMessageService.getHistory(1L, 999L))
                .thenThrow(BizException("Session not found or access denied"))

            assertThrows<BizException> {
                controller.getHistory(999L)
            }
        }
    }

    @Nested
    @DisplayName("POST /api/admin/mp/chat/history/{sessionId}")
    inner class SaveMessagesEndpoint {

        @Test
        @DisplayName("saveMessages - 已登录时批量保存成功")
        fun `saveMessages should save messages when logged in`() {
            loginAs("mpuser")
            val messages = listOf(
                MpChatMessageDto(role = "user", content = "Hello"),
                MpChatMessageDto(role = "assistant", content = "Hi"),
            )

            val result = controller.saveMessages(100L, messages)

            assertTrue(result.isSuccess())
            verify(mpMessageService).saveMessages(1L, 100L, messages)
        }

        @Test
        @DisplayName("saveMessages - 空消息列表也返回成功")
        fun `saveMessages should succeed with empty message list`() {
            loginAs("mpuser")

            val result = controller.saveMessages(100L, emptyList())

            assertTrue(result.isSuccess())
            verify(mpMessageService).saveMessages(1L, 100L, emptyList())
        }

        @Test
        @DisplayName("saveMessages - 未登录时返回错误")
        fun `saveMessages should return error when not logged in`() {
            SecurityContextHolder.clearContext()

            val result = controller.saveMessages(100L, listOf(MpChatMessageDto(role = "user", content = "Hello")))

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
            verify(mpMessageService, never()).saveMessages(any(), any(), any())
        }

        @Test
        @DisplayName("saveMessages - 会话不属于当前用户时异常向上传播")
        fun `saveMessages should propagate exception when session not owned`() {
            loginAs("mpuser")
            doThrow(BizException("Session not found or access denied"))
                .`when`(mpMessageService).saveMessages(eq(1L), eq(999L), any())

            assertThrows<BizException> {
                controller.saveMessages(999L, listOf(MpChatMessageDto(role = "user", content = "Hello")))
            }
        }
    }

    @Nested
    @DisplayName("DELETE /api/admin/mp/chat/history/{sessionId}")
    inner class DeleteHistoryEndpoint {

        @Test
        @DisplayName("deleteHistory - 已登录时删除成功")
        fun `deleteHistory should delete when logged in`() {
            loginAs("mpuser")

            val result = controller.deleteHistory(100L)

            assertTrue(result.isSuccess())
            verify(mpMessageService).deleteHistory(1L, 100L)
        }

        @Test
        @DisplayName("deleteHistory - 未登录时返回错误")
        fun `deleteHistory should return error when not logged in`() {
            SecurityContextHolder.clearContext()

            val result = controller.deleteHistory(100L)

            assertFalse(result.isSuccess())
            assertEquals("User not logged in", result.message)
            verify(mpMessageService, never()).deleteHistory(any(), any())
        }

        @Test
        @DisplayName("deleteHistory - 会话不属于当前用户时异常向上传播")
        fun `deleteHistory should propagate exception when session not owned`() {
            loginAs("mpuser")
            doThrow(BizException("Session not found or access denied"))
                .`when`(mpMessageService).deleteHistory(1L, 999L)

            assertThrows<BizException> {
                controller.deleteHistory(999L)
            }
        }
    }
}
