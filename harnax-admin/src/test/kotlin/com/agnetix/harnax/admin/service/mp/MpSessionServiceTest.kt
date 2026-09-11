package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.dto.mp.MpCreateSessionRequest
import com.agnetix.harnax.admin.dto.mp.MpUpdateSessionRequest
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.entity.Agent
import com.agnetix.harnax.entity.MpSession
import com.agnetix.harnax.entity.Session
import com.agnetix.harnax.mapper.AgentMapper
import com.agnetix.harnax.mapper.MpChatMessageMapper
import com.agnetix.harnax.mapper.MpSessionMapper
import com.agnetix.harnax.mapper.SessionMapper
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
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.quality.Strictness

/**
 * MpSessionService 单元测试
 * 测试移动端会话的查询、创建、更新与删除逻辑
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MpSessionServiceTest {

    @Mock
    private lateinit var mpSessionMapper: MpSessionMapper

    @Mock
    private lateinit var mpChatMessageMapper: MpChatMessageMapper

    @Mock
    private lateinit var agentMapper: AgentMapper

    @Mock
    private lateinit var sessionMapper: SessionMapper

    @InjectMocks
    private lateinit var mpSessionService: MpSessionService

    private lateinit var testAgent: Agent
    private lateinit var testMpSession: MpSession

    @BeforeEach
    fun setUp() {
        testAgent = Agent().apply {
            id = 10L
            tenantId = 5L
            name = "客服Agent"
            description = "智能客服"
            systemPrompt = "You are helpful"
            modelId = 100L
            status = 1
            active = 1
        }

        testMpSession = MpSession().apply {
            id = 100L
            userId = 1L
            sessionName = "测试会话"
            routerSessionId = "mp-router-1"
            agentId = 10L
            status = 1
        }

        `when`(mpChatMessageMapper.countBySessionId(anyLong())).thenReturn(0)
    }

    @Nested
    @DisplayName("查询会话列表测试")
    inner class ListSessionsTests {

        @Test
        @DisplayName("listSessions - 返回用户会话列表含Agent名和消息数")
        fun `listSessions should return sessions with agent name and message count`() {
            // Given
            `when`(mpSessionMapper.selectByUserId(1L)).thenReturn(listOf(testMpSession))
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent)
            `when`(mpChatMessageMapper.countBySessionId(100L)).thenReturn(7)

            // When
            val result = mpSessionService.listSessions(1L)

            // Then
            assertEquals(1, result.size)
            assertEquals(100L, result[0].id)
            assertEquals("测试会话", result[0].sessionName)
            assertEquals("mp-router-1", result[0].routerSessionId)
            assertEquals(10L, result[0].agentId)
            assertEquals("客服Agent", result[0].agentName)
            assertEquals(7, result[0].messageCount)
        }

        @Test
        @DisplayName("listSessions - Agent不存在时Agent名为空字符串")
        fun `listSessions should use empty agent name when agent missing`() {
            // Given
            `when`(mpSessionMapper.selectByUserId(1L)).thenReturn(listOf(testMpSession))
            `when`(agentMapper.selectById(10L)).thenReturn(null)

            // When
            val result = mpSessionService.listSessions(1L)

            // Then
            assertEquals("", result[0].agentName)
        }

        @Test
        @DisplayName("listSessions - agentId为0时不查询Agent")
        fun `listSessions should not query agent when agentId is zero`() {
            // Given
            val sessionWithoutAgent = MpSession().apply {
                id = 101L
                userId = 1L
                sessionName = "无Agent会话"
                agentId = 0L
            }
            `when`(mpSessionMapper.selectByUserId(1L)).thenReturn(listOf(sessionWithoutAgent))

            // When
            val result = mpSessionService.listSessions(1L)

            // Then
            assertEquals("", result[0].agentName)
            verify(agentMapper, never()).selectById(anyLong())
        }

        @Test
        @DisplayName("listSessions - 无会话返回空列表")
        fun `listSessions should return empty list when no sessions`() {
            `when`(mpSessionMapper.selectByUserId(1L)).thenReturn(emptyList())

            assertTrue(mpSessionService.listSessions(1L).isEmpty())
        }
    }

    @Nested
    @DisplayName("创建会话测试")
    inner class CreateSessionTests {

        private val createRequest = MpCreateSessionRequest(sessionName = "新会话", agentId = 10L)

        @Test
        @DisplayName("createSession - 同时创建router会话和移动端会话")
        fun `createSession should insert router session and mp session`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent)

            // When
            val response = mpSessionService.createSession(1L, 5L, createRequest)

            // Then
            assertEquals("新会话", response.sessionName)
            assertEquals(10L, response.agentId)
            assertEquals("客服Agent", response.agentName)
            assertTrue(response.routerSessionId.startsWith("mp-"), response.routerSessionId)

            // 校验 router 会话字段来自 agent 快照
            val sessionCaptor = argumentCaptor<Session>()
            verify(sessionMapper, times(1)).insert(sessionCaptor.capture())
            val session = sessionCaptor.firstValue
            assertEquals(10L, session.agentId)
            assertEquals("客服Agent", session.name)
            assertEquals("新会话", session.title)
            assertEquals("You are helpful", session.systemPrompt)
            assertEquals(100L, session.modelId)
            assertEquals(5L, session.tenantId)
            assertEquals("1", session.owner)
            assertEquals(0, session.isPublic)

            // 校验移动端会话
            val mpCaptor = argumentCaptor<MpSession>()
            verify(mpSessionMapper, times(1)).insert(mpCaptor.capture())
            val mpSession = mpCaptor.firstValue
            assertEquals(1L, mpSession.userId)
            assertEquals("新会话", mpSession.sessionName)
            assertEquals(session.sessionId, mpSession.routerSessionId)
            assertEquals(10L, mpSession.agentId)
        }

        @Test
        @DisplayName("createSession - 未传tenantId时使用Agent的tenantId")
        fun `createSession should fall back to agent tenantId when tenantId is null`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent)

            // When
            mpSessionService.createSession(1L, null, createRequest)

            // Then
            val sessionCaptor = argumentCaptor<Session>()
            verify(sessionMapper).insert(sessionCaptor.capture())
            assertEquals(5L, sessionCaptor.firstValue.tenantId)
        }

        @Test
        @DisplayName("createSession - 缺少agentId抛出异常")
        fun `createSession should throw when agentId missing`() {
            // When & Then
            val ex = assertThrows<BizException> {
                mpSessionService.createSession(1L, 5L, MpCreateSessionRequest(sessionName = "新会话", agentId = null))
            }
            assertEquals("Agent ID is required", ex.message)
            verify(sessionMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createSession - Agent不存在抛出异常")
        fun `createSession should throw when agent not found`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(null)

            // When & Then
            val ex = assertThrows<BizException> {
                mpSessionService.createSession(1L, 5L, createRequest)
            }
            assertEquals("Agent not found", ex.message)
            verify(sessionMapper, never()).insert(any())
        }

        @Test
        @DisplayName("createSession - Agent已删除抛出异常")
        fun `createSession should throw when agent is inactive`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent.apply { active = 0 })

            // When & Then
            val ex = assertThrows<BizException> {
                mpSessionService.createSession(1L, 5L, createRequest)
            }
            assertEquals("Agent is not available", ex.message)
        }

        @Test
        @DisplayName("createSession - Agent被禁用抛出异常")
        fun `createSession should throw when agent is disabled`() {
            // Given
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent.apply { status = 0 })

            // When & Then
            val ex = assertThrows<BizException> {
                mpSessionService.createSession(1L, 5L, createRequest)
            }
            assertEquals("Agent is not available", ex.message)
            verify(mpSessionMapper, never()).insert(any())
        }
    }

    @Nested
    @DisplayName("更新会话测试")
    inner class UpdateSessionTests {

        private val updateRequest = MpUpdateSessionRequest(sessionName = "改名后的会话")

        @Test
        @DisplayName("updateSession - 更新移动端会话并同步router会话标题")
        fun `updateSession should update mp session and sync router session title`() {
            // Given
            `when`(mpSessionMapper.selectByIdAndUserId(100L, 1L)).thenReturn(testMpSession)
            val routerSession = Session().apply {
                id = 200L
                sessionId = "mp-router-1"
                title = "测试会话"
                name = "测试会话"
                status = 1
            }
            `when`(sessionMapper.selectBySessionIdAndStatus("mp-router-1", 1)).thenReturn(routerSession)
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent)

            // When
            val response = mpSessionService.updateSession(1L, 100L, updateRequest)

            // Then
            assertEquals("改名后的会话", response.sessionName)

            val mpCaptor = argumentCaptor<MpSession>()
            verify(mpSessionMapper, times(1)).updateById(mpCaptor.capture())
            assertEquals("改名后的会话", mpCaptor.firstValue.sessionName)

            val routerCaptor = argumentCaptor<Session>()
            verify(sessionMapper, times(1)).updateById(routerCaptor.capture())
            assertEquals("改名后的会话", routerCaptor.firstValue.title)
            assertEquals("改名后的会话", routerCaptor.firstValue.name)
        }

        @Test
        @DisplayName("updateSession - router会话不存在时只更新移动端会话")
        fun `updateSession should skip router update when router session missing`() {
            // Given
            `when`(mpSessionMapper.selectByIdAndUserId(100L, 1L)).thenReturn(testMpSession)
            `when`(sessionMapper.selectBySessionIdAndStatus("mp-router-1", 1)).thenReturn(null)
            `when`(agentMapper.selectById(10L)).thenReturn(testAgent)

            // When
            val response = mpSessionService.updateSession(1L, 100L, updateRequest)

            // Then
            assertEquals("改名后的会话", response.sessionName)
            verify(mpSessionMapper, times(1)).updateById(any())
            verify(sessionMapper, never()).updateById(any())
        }

        @Test
        @DisplayName("updateSession - 会话不存在或无权限抛出异常")
        fun `updateSession should throw when session not owned by user`() {
            // Given
            `when`(mpSessionMapper.selectByIdAndUserId(100L, 2L)).thenReturn(null)

            // When & Then
            val ex = assertThrows<BizException> {
                mpSessionService.updateSession(2L, 100L, updateRequest)
            }
            assertEquals("Session not found or access denied", ex.message)
            verify(mpSessionMapper, never()).updateById(any())
        }
    }

    @Nested
    @DisplayName("删除会话测试")
    inner class DeleteSessionTests {

        @Test
        @DisplayName("deleteSession - 级联删除router会话、移动端会话和消息")
        fun `deleteSession should cascade delete router session mp session and messages`() {
            // Given
            `when`(mpSessionMapper.selectByIdAndUserId(100L, 1L)).thenReturn(testMpSession)
            val routerSession = Session().apply {
                id = 200L
                sessionId = "mp-router-1"
                status = 1
            }
            `when`(sessionMapper.selectBySessionIdAndStatus("mp-router-1", 1)).thenReturn(routerSession)

            // When
            mpSessionService.deleteSession(1L, 100L)

            // Then
            verify(sessionMapper, times(1)).deleteById(200L)
            verify(mpSessionMapper, times(1)).deleteById(100L)
            verify(mpChatMessageMapper, times(1)).deleteBySessionId(100L)
        }

        @Test
        @DisplayName("deleteSession - router会话不存在时仍删除移动端会话和消息")
        fun `deleteSession should still delete mp session when router session missing`() {
            // Given
            `when`(mpSessionMapper.selectByIdAndUserId(100L, 1L)).thenReturn(testMpSession)
            `when`(sessionMapper.selectBySessionIdAndStatus("mp-router-1", 1)).thenReturn(null)

            // When
            mpSessionService.deleteSession(1L, 100L)

            // Then
            verify(sessionMapper, never()).deleteById(anyLong())
            verify(mpSessionMapper, times(1)).deleteById(100L)
            verify(mpChatMessageMapper, times(1)).deleteBySessionId(100L)
        }

        @Test
        @DisplayName("deleteSession - 会话不存在或无权限抛出异常")
        fun `deleteSession should throw when session not owned by user`() {
            // Given
            `when`(mpSessionMapper.selectByIdAndUserId(100L, 2L)).thenReturn(null)

            // When & Then
            val ex = assertThrows<BizException> {
                mpSessionService.deleteSession(2L, 100L)
            }
            assertEquals("Session not found or access denied", ex.message)
            verify(mpSessionMapper, never()).deleteById(anyLong())
            verify(mpChatMessageMapper, never()).deleteBySessionId(anyLong())
        }
    }
}
