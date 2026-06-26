package com.agnetix.harnax.agent.protocol

import com.agnetix.harnax.common.error.HarnaxErrorCode
import com.agnetix.harnax.common.error.HarnaxException
import io.agentscope.core.model.ChatUsage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

class ChatEventTest {

    // ==================== TokenUsage ====================

    @Nested
    inner class TokenUsageTests {
        @Test
        fun `fromChatUsage returns null for null input`() {
            assertNull(TokenUsage.fromChatUsage(null))
        }

        @Test
        fun `fromChatUsage creates TokenUsage with correct fields`() {
            val usage = mock(ChatUsage::class.java)
            `when`(usage.inputTokens).thenReturn(10)
            `when`(usage.outputTokens).thenReturn(20)
            `when`(usage.totalTokens).thenReturn(30)
            `when`(usage.time).thenReturn(1.5)

            val result = TokenUsage.fromChatUsage(usage)

            assertNotNull(result)
            assertEquals(10, result!!.inputTokens)
            assertEquals(20, result.outputTokens)
            assertEquals(30, result.totalTokens)
            assertEquals(1.5, result.costTime)
            assertTrue(result.timestamp > 0)
        }

        @Test
        fun `fromChatUsage captures timestamp near current time`() {
            val usage = mock(ChatUsage::class.java)
            `when`(usage.inputTokens).thenReturn(0)
            `when`(usage.outputTokens).thenReturn(0)
            `when`(usage.totalTokens).thenReturn(0)
            `when`(usage.time).thenReturn(0.0)

            val before = System.currentTimeMillis()
            val result = TokenUsage.fromChatUsage(usage)!!
            val after = System.currentTimeMillis()

            assertTrue(result.timestamp in before..after)
        }
    }

    // ==================== ErrorChatEvent ====================

    @Nested
    inner class ErrorChatEventFactory {
        @Test
        fun `from HarnaxException creates ErrorChatEvent with code and message`() {
            val exception = HarnaxException("6001", "模型配置未找到")
            val event = ErrorChatEvent.from(exception)

            assertEquals("6001", event.code)
            assertEquals("模型配置未找到", event.message)
            assertEquals(EventType.ErrorEvent, event.eventType)
            assertNull(event.tokenUsage)
        }

        @Test
        fun `from HarnaxErrorCode without args uses default message`() {
            val event = ErrorChatEvent.from(HarnaxErrorCode.SYSTEM_ERROR)

            assertEquals("1001", event.code)
            assertEquals("系统内部错误", event.message)
        }

        @Test
        fun `from HarnaxErrorCode with args formats message`() {
            val event = ErrorChatEvent.from(HarnaxErrorCode.INVALID_PARAM, "userId")

            assertEquals("1003", event.code)
            assertEquals("参数 [userId] 无效", event.message)
        }

        @Test
        fun `from HarnaxErrorCode with multiple args`() {
            val event = ErrorChatEvent.from(HarnaxErrorCode.USER_NOT_FOUND, "admin")

            assertEquals("2006", event.code)
            assertEquals("用户 [admin] 不存在", event.message)
        }
    }

    // ==================== StreamTextChatEvent ====================

    @Nested
    inner class StreamTextChatEventTests {
        @Test
        fun `eventType is TextEvent`() {
            val event = StreamTextChatEvent("hello", false, null)
            assertEquals(EventType.TextEvent, event.eventType)
        }

        @Test
        fun `fields are correctly set`() {
            val usage = TokenUsage(1, 2, 3, 0.5, 1000L)
            val event = StreamTextChatEvent("chunk", true, usage)

            assertEquals("chunk", event.message)
            assertTrue(event.isLast)
            assertEquals(usage, event.tokenUsage)
        }
    }

    // ==================== StreamThinkingChatEvent ====================

    @Nested
    inner class StreamThinkingChatEventTests {
        @Test
        fun `eventType is ThinkingEvent`() {
            val event = StreamThinkingChatEvent("thinking...", false, null)
            assertEquals(EventType.ThinkingEvent, event.eventType)
        }

        @Test
        fun `fields are correctly set`() {
            val event = StreamThinkingChatEvent("deep thought", true, null)

            assertEquals("deep thought", event.message)
            assertTrue(event.isLast)
            assertNull(event.tokenUsage)
        }
    }

    // ==================== CallToolChatEvent ====================

    @Nested
    inner class CallToolChatEventTests {
        @Test
        fun `eventType is CallToolEvent`() {
            val event = CallToolChatEvent("t1", "search", mapOf("q" to "test"), null)
            assertEquals(EventType.CallToolEvent, event.eventType)
        }

        @Test
        fun `fields are correctly set`() {
            val args = mapOf<String, Any>("query" to "hello", "limit" to 10)
            val usage = TokenUsage(5, 5, 10, 1.0, 2000L)
            val event = CallToolChatEvent("call_1", "web_search", args, usage)

            assertEquals("call_1", event.toolId)
            assertEquals("web_search", event.toolName)
            assertEquals(args, event.arguments)
            assertEquals(usage, event.tokenUsage)
        }
    }

    // ==================== ToolResultChatEvent ====================

    @Nested
    inner class ToolResultChatEventTests {
        @Test
        fun `eventType is ToolResultEvent`() {
            val event = ToolResultChatEvent("t1", "search", "result", true, null)
            assertEquals(EventType.ToolResultEvent, event.eventType)
        }

        @Test
        fun `default success is true`() {
            val event = ToolResultChatEvent("t1", "exec", "ok", tokenUsage = null)
            assertTrue(event.success)
        }

        @Test
        fun `success can be false`() {
            val event = ToolResultChatEvent("t1", "exec", "error occurred", false, null)
            assertFalse(event.success)
            assertEquals("error occurred", event.message)
        }
    }

    // ==================== ToolConfirmChatEvent ====================

    @Nested
    inner class ToolConfirmChatEventTests {
        @Test
        fun `eventType is ToolConfirmEvent`() {
            val tools = listOf(
                PendingCallTool("t1", "dangerous_op", mapOf("target" to "db"), true),
            )
            val event = ToolConfirmChatEvent(tools, null)

            assertEquals(EventType.ToolConfirmEvent, event.eventType)
            assertEquals(1, event.pendingCallTools.size)
        }

        @Test
        fun `PendingCallTool fields are correctly set`() {
            val tool = PendingCallTool("id-1", "delete_all", mapOf("table" to "users"), true)

            assertEquals("id-1", tool.toolId)
            assertEquals("delete_all", tool.toolName)
            assertEquals(mapOf("table" to "users"), tool.arguments)
            assertTrue(tool.isDangerous)
        }

        @Test
        fun `multiple pending tools`() {
            val tools = listOf(
                PendingCallTool("t1", "op1", emptyMap(), false),
                PendingCallTool("t2", "op2", mapOf("x" to 1), true),
            )
            val event = ToolConfirmChatEvent(tools, null)

            assertEquals(2, event.pendingCallTools.size)
            assertFalse(event.pendingCallTools[0].isDangerous)
            assertTrue(event.pendingCallTools[1].isDangerous)
        }
    }

    // ==================== EndEventChatEvent ====================

    @Nested
    inner class EndEventChatEventTests {
        @Test
        fun `eventType is EndEvent`() {
            val event = EndEventChatEvent()
            assertEquals(EventType.EndEvent, event.eventType)
        }

        @Test
        fun `default tokenUsage is null`() {
            val event = EndEventChatEvent()
            assertNull(event.tokenUsage)
        }

        @Test
        fun `can carry tokenUsage`() {
            val usage = TokenUsage(100, 50, 150, 3.0, 5000L)
            val event = EndEventChatEvent(usage)

            assertEquals(usage, event.tokenUsage)
        }
    }

    // ==================== EventType enum ====================

    @Nested
    inner class EventTypeTests {
        @Test
        fun `all event types are defined`() {
            val types = EventType.entries
            assertEquals(7, types.size)
            assertTrue(types.contains(EventType.ThinkingEvent))
            assertTrue(types.contains(EventType.CallToolEvent))
            assertTrue(types.contains(EventType.ToolResultEvent))
            assertTrue(types.contains(EventType.TextEvent))
            assertTrue(types.contains(EventType.ToolConfirmEvent))
            assertTrue(types.contains(EventType.EndEvent))
            assertTrue(types.contains(EventType.ErrorEvent))
        }
    }

    // ==================== RequestType enum ====================

    @Nested
    inner class RequestTypeTests {
        @Test
        fun `all request types are defined`() {
            val types = RequestType.entries
            assertEquals(3, types.size)
            assertTrue(types.contains(RequestType.CHAT))
            assertTrue(types.contains(RequestType.COMMAND))
            assertTrue(types.contains(RequestType.CONFIRM))
        }
    }

    // ==================== ToolInfo ====================

    @Nested
    inner class ToolInfoTests {
        @Test
        fun `default values are null`() {
            val info = ToolInfo()
            assertNull(info.toolId)
            assertNull(info.toolName)
        }

        @Test
        fun `fields are correctly set`() {
            val info = ToolInfo(toolId = "tid-1", toolName = "read_file")
            assertEquals("tid-1", info.toolId)
            assertEquals("read_file", info.toolName)
        }
    }
}
