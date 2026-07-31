package com.agnetix.harnax.admin.service.mp

import com.agnetix.harnax.admin.dto.mp.MpChatMessageDto
import com.agnetix.harnax.admin.exception.BizException
import com.agnetix.harnax.entity.MpChatMessage
import com.agnetix.harnax.entity.MpSession
import com.agnetix.harnax.mapper.MpChatMessageMapper
import com.agnetix.harnax.mapper.MpSessionMapper
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
 * MpMessageService 单元测试
 * 测试移动端聊天消息的查询、保存与删除逻辑
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MpMessageServiceTest {

    @Mock
    private lateinit var mpChatMessageMapper: MpChatMessageMapper

    @Mock
    private lateinit var mpSessionMapper: MpSessionMapper

    @InjectMocks
    private lateinit var mpMessageService: MpMessageService

    private lateinit var testSession: MpSession

    @BeforeEach
    fun setUp() {
        testSession = MpSession().apply {
            id = 100L
            userId = 1L
            sessionName = "测试会话"
            routerSessionId = "mp-router-1"
            agentId = 10L
            status = 1
        }

        // 默认: 会话归属校验通过
        `when`(mpSessionMapper.selectByIdAndUserId(100L, 1L)).thenReturn(testSession)
    }

    @Nested
    @DisplayName("查询消息历史测试")
    inner class GetHistoryTests {

        @Test
        @DisplayName("getHistory - 返回会话消息列表并转换为DTO")
        fun `getHistory should return messages mapped to dto`() {
            // Given
            val entity = MpChatMessage().apply {
                id = 1L
                sessionId = 100L
                role = "assistant"
                content = "你好"
                segmentsJson = """[{"type":"text"}]"""
                tokenUsageJson = """{"total":10}"""
                imageUrlsJson = """["http://img/1.png"]"""
            }
            `when`(mpChatMessageMapper.selectBySessionId(100L)).thenReturn(listOf(entity))

            // When
            val result = mpMessageService.getHistory(1L, 100L)

            // Then
            assertEquals(1, result.size)
            assertEquals("assistant", result[0].role)
            assertEquals("你好", result[0].content)
            assertEquals("""[{"type":"text"}]""", result[0].segmentsJson)
            assertEquals("""{"total":10}""", result[0].tokenUsageJson)
            assertEquals("""["http://img/1.png"]""", result[0].imageUrlsJson)
        }

        @Test
        @DisplayName("getHistory - 无消息返回空列表")
        fun `getHistory should return empty list when no messages`() {
            // Given
            `when`(mpChatMessageMapper.selectBySessionId(100L)).thenReturn(emptyList())

            // When
            val result = mpMessageService.getHistory(1L, 100L)

            // Then
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("getHistory - 会话不存在或无权限抛出异常")
        fun `getHistory should throw when session not owned by user`() {
            // Given
            `when`(mpSessionMapper.selectByIdAndUserId(100L, 2L)).thenReturn(null)

            // When & Then
            val ex = assertThrows<BizException> {
                mpMessageService.getHistory(2L, 100L)
            }
            assertEquals("Session not found or access denied", ex.message)
            verify(mpChatMessageMapper, never()).selectBySessionId(anyLong())
        }
    }

    @Nested
    @DisplayName("批量保存消息测试")
    inner class SaveMessagesTests {

        @Test
        @DisplayName("saveMessages - 批量插入消息实体")
        fun `saveMessages should batch insert entities`() {
            // Given
            val dtos = listOf(
                MpChatMessageDto(role = "user", content = "问题", segmentsJson = "[]"),
                MpChatMessageDto(
                    role = "assistant",
                    content = "回答",
                    segmentsJson = """[{"type":"text"}]""",
                    tokenUsageJson = """{"total":5}""",
                    imageUrlsJson = null,
                ),
            )

            // When
            mpMessageService.saveMessages(1L, 100L, dtos)

            // Then
            val captor = argumentCaptor<List<MpChatMessage>>()
            verify(mpChatMessageMapper, times(1)).batchInsert(captor.capture())
            val entities = captor.firstValue
            assertEquals(2, entities.size)
            assertEquals(100L, entities[0].sessionId)
            assertEquals("user", entities[0].role)
            assertEquals("问题", entities[0].content)
            assertEquals("assistant", entities[1].role)
            assertEquals("""{"total":5}""", entities[1].tokenUsageJson)
            assertNull(entities[1].imageUrlsJson)
            assertNotNull(entities[0].createTime)
        }

        @Test
        @DisplayName("saveMessages - 空列表不执行插入")
        fun `saveMessages should skip insert when messages empty`() {
            // When
            mpMessageService.saveMessages(1L, 100L, emptyList())

            // Then
            verify(mpChatMessageMapper, never()).batchInsert(any())
        }

        @Test
        @DisplayName("saveMessages - 会话不存在或无权限抛出异常")
        fun `saveMessages should throw when session not owned by user`() {
            // Given
            `when`(mpSessionMapper.selectByIdAndUserId(100L, 2L)).thenReturn(null)

            // When & Then
            assertThrows<BizException> {
                mpMessageService.saveMessages(2L, 100L, listOf(MpChatMessageDto()))
            }
            verify(mpChatMessageMapper, never()).batchInsert(any())
        }
    }

    @Nested
    @DisplayName("删除消息历史测试")
    inner class DeleteHistoryTests {

        @Test
        @DisplayName("deleteHistory - 删除会话全部消息")
        fun `deleteHistory should delete all messages of session`() {
            // When
            mpMessageService.deleteHistory(1L, 100L)

            // Then
            verify(mpChatMessageMapper, times(1)).deleteBySessionId(100L)
        }

        @Test
        @DisplayName("deleteHistory - 会话不存在或无权限抛出异常")
        fun `deleteHistory should throw when session not owned by user`() {
            // Given
            `when`(mpSessionMapper.selectByIdAndUserId(100L, 2L)).thenReturn(null)

            // When & Then
            val ex = assertThrows<BizException> {
                mpMessageService.deleteHistory(2L, 100L)
            }
            assertEquals("Session not found or access denied", ex.message)
            verify(mpChatMessageMapper, never()).deleteBySessionId(anyLong())
        }
    }
}
