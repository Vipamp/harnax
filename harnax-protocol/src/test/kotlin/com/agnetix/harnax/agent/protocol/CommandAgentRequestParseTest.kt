package com.agnetix.harnax.agent.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CommandAgentRequestParseTest {

    // ==================== parse valid commands ====================

    @Nested
    inner class ValidCommands {
        @Test
        fun `parse clear command`() {
            val result = CommandAgentRequest.parse("session-1", "/clear")

            assertNotNull(result)
            assertEquals(CommandType.CLEAR, result!!.command)
            assertEquals("", result.args)
            assertEquals("session-1", result.sessionId)
        }

        @Test
        fun `parse stop command with args`() {
            val result = CommandAgentRequest.parse("session-1", "/stop some-session")

            assertNotNull(result)
            assertEquals(CommandType.INTERRUPT, result!!.command)
            assertEquals("some-session", result.args)
        }

        @Test
        fun `parse interrupt command`() {
            val result = CommandAgentRequest.parse("session-1", "/interrupt")

            assertNotNull(result)
            assertEquals(CommandType.INTERRUPT, result!!.command)
            assertEquals("", result.args)
        }

        @Test
        fun `parse compact command with token limit`() {
            val result = CommandAgentRequest.parse("session-1", "/compact 500")

            assertNotNull(result)
            assertEquals(CommandType.COMPACT, result!!.command)
            assertEquals("500", result.args)
        }

        @Test
        fun `parse approve command`() {
            val result = CommandAgentRequest.parse("session-1", "/approve")

            assertNotNull(result)
            assertEquals(CommandType.APPROVE, result!!.command)
        }

        @Test
        fun `parse stop-sandbox command`() {
            val result = CommandAgentRequest.parse("session-1", "/stop-sandbox")

            assertNotNull(result)
            assertEquals(CommandType.STOP_SANDBOX, result!!.command)
        }

        @Test
        fun `parse is case insensitive`() {
            val result = CommandAgentRequest.parse("session-1", "/CLEAR")

            assertNotNull(result)
            assertEquals(CommandType.CLEAR, result!!.command)
        }

        @Test
        fun `parse with mixed case`() {
            val result = CommandAgentRequest.parse("session-1", "/Stop arg1")

            assertNotNull(result)
            assertEquals(CommandType.INTERRUPT, result!!.command)
            assertEquals("arg1", result.args)
        }

        @Test
        fun `parse with extra whitespace in args`() {
            val result = CommandAgentRequest.parse("session-1", "/compact   1000  ")

            assertNotNull(result)
            assertEquals(CommandType.COMPACT, result!!.command)
            assertEquals("1000", result.args)
        }

        @Test
        fun `parse command with trailing space but no args`() {
            val result = CommandAgentRequest.parse("session-1", "/clear ")

            assertNotNull(result)
            assertEquals(CommandType.CLEAR, result!!.command)
            assertEquals("", result.args)
        }

        @Test
        fun `parse command with multi-word args preserves full text`() {
            val result = CommandAgentRequest.parse("session-1", "/stop some reason here")

            assertNotNull(result)
            assertEquals(CommandType.INTERRUPT, result!!.command)
            assertEquals("some reason here", result.args)
        }
    }

    // ==================== parse invalid commands ====================

    @Nested
    inner class InvalidCommands {
        @Test
        fun `parse returns null for non-slash text`() {
            val result = CommandAgentRequest.parse("session-1", "hello world")

            assertNull(result)
        }

        @Test
        fun `parse returns null for slash only`() {
            val result = CommandAgentRequest.parse("session-1", "/")

            assertNull(result)
        }

        @Test
        fun `parse returns null for slash with spaces only`() {
            val result = CommandAgentRequest.parse("session-1", "/   ")

            assertNull(result)
        }

        @Test
        fun `parse returns null for unknown command`() {
            val result = CommandAgentRequest.parse("session-1", "/unknown-cmd")

            assertNull(result)
        }

        @Test
        fun `parse returns null for empty string`() {
            val result = CommandAgentRequest.parse("session-1", "")

            assertNull(result)
        }
    }

    // ==================== CommandType fromKeyword ====================

    @Nested
    inner class CommandTypeFromKeyword {
        @Test
        fun `fromKeyword resolves interrupt aliases`() {
            assertEquals(CommandType.INTERRUPT, CommandType.fromKeyword("interrupt"))
            assertEquals(CommandType.INTERRUPT, CommandType.fromKeyword("stop"))
        }

        @Test
        fun `fromKeyword resolves clear`() {
            assertEquals(CommandType.CLEAR, CommandType.fromKeyword("clear"))
        }

        @Test
        fun `fromKeyword resolves compact`() {
            assertEquals(CommandType.COMPACT, CommandType.fromKeyword("compact"))
        }

        @Test
        fun `fromKeyword resolves approve`() {
            assertEquals(CommandType.APPROVE, CommandType.fromKeyword("approve"))
        }

        @Test
        fun `fromKeyword resolves stop-sandbox`() {
            assertEquals(CommandType.STOP_SANDBOX, CommandType.fromKeyword("stop-sandbox"))
        }

        @Test
        fun `fromKeyword returns null for unknown`() {
            assertNull(CommandType.fromKeyword("foobar"))
        }

        @Test
        fun `fromKeyword is case insensitive`() {
            assertEquals(CommandType.CLEAR, CommandType.fromKeyword("CLEAR"))
            assertEquals(CommandType.INTERRUPT, CommandType.fromKeyword("STOP"))
        }

        @Test
        fun `fromKeyword returns null for empty string`() {
            assertNull(CommandType.fromKeyword(""))
        }
    }

    // ==================== AgentRequest withSessionId ====================

    @Nested
    inner class WithSessionId {
        @Test
        fun `withSessionId creates new ChatAgentRequest with replaced sessionId`() {
            val original = ChatAgentRequest(sessionId = "old", message = "hello")
            val replaced = original.withSessionId("new")

            assertEquals("new", replaced.sessionId)
            assertEquals("hello", (replaced as ChatAgentRequest).message)
            assertNotSame(original, replaced)
        }

        @Test
        fun `withSessionId creates new CommandAgentRequest with replaced sessionId`() {
            val original = CommandAgentRequest(sessionId = "old", command = CommandType.CLEAR)
            val replaced = original.withSessionId("new")

            assertEquals("new", replaced.sessionId)
            assertEquals(CommandType.CLEAR, (replaced as CommandAgentRequest).command)
        }

        @Test
        fun `withSessionId creates new ConfirmAgentRequest with replaced sessionId`() {
            val original = ConfirmAgentRequest(sessionId = "old", isConfirmed = true)
            val replaced = original.withSessionId("new")

            assertEquals("new", replaced.sessionId)
            assertTrue((replaced as ConfirmAgentRequest).isConfirmed)
        }

        @Test
        fun `withSessionId preserves imageUrls in ChatAgentRequest`() {
            val original = ChatAgentRequest(
                sessionId = "old",
                message = "hello",
                imageUrls = listOf("https://example.com/img.png"),
                requestId = "req-1",
            )
            val replaced = original.withSessionId("new") as ChatAgentRequest

            assertEquals("new", replaced.sessionId)
            assertEquals("hello", replaced.message)
            assertEquals(listOf("https://example.com/img.png"), replaced.imageUrls)
            assertEquals("req-1", replaced.requestId)
        }

        @Test
        fun `withSessionId preserves args in CommandAgentRequest`() {
            val original = CommandAgentRequest(
                sessionId = "old",
                command = CommandType.COMPACT,
                args = "500",
            )
            val replaced = original.withSessionId("new") as CommandAgentRequest

            assertEquals("new", replaced.sessionId)
            assertEquals(CommandType.COMPACT, replaced.command)
            assertEquals("500", replaced.args)
        }

        @Test
        fun `withSessionId preserves toolInfoList in ConfirmAgentRequest`() {
            val tools = listOf(ToolInfo(toolId = "t1", toolName = "delete"))
            val original = ConfirmAgentRequest(
                sessionId = "old",
                isConfirmed = false,
                toolInfoList = tools,
            )
            val replaced = original.withSessionId("new") as ConfirmAgentRequest

            assertEquals("new", replaced.sessionId)
            assertFalse(replaced.isConfirmed)
            assertEquals(1, replaced.toolInfoList.size)
            assertEquals("t1", replaced.toolInfoList[0].toolId)
        }
    }

    // ==================== ChatResponse fromEvents ====================

    @Nested
    inner class ChatResponseFromEvents {
        @Test
        fun `fromEvents aggregates text events`() {
            val events = listOf<ChatEvent>(
                StreamTextChatEvent("Hello ", false, null),
                StreamTextChatEvent("World", true, null),
                EndEventChatEvent(),
            )

            val response = ChatResponse.fromEvents("session-1", events)

            assertEquals("Hello World", response.content)
            assertEquals("session-1", response.sessionId)
        }

        @Test
        fun `fromEvents aggregates thinking events`() {
            val events = listOf<ChatEvent>(
                StreamThinkingChatEvent("Let me think", false, null),
                StreamThinkingChatEvent(" about it", true, null),
                EndEventChatEvent(),
            )

            val response = ChatResponse.fromEvents("session-1", events)

            assertEquals("Let me think about it", response.thinking)
        }

        @Test
        fun `fromEvents captures last token usage`() {
            val usage1 = TokenUsage(10, 20, 30, 1.0, 1000L)
            val usage2 = TokenUsage(15, 25, 40, 2.0, 2000L)

            val events = listOf<ChatEvent>(
                StreamTextChatEvent("a", false, usage1),
                StreamTextChatEvent("b", true, usage2),
                EndEventChatEvent(),
            )

            val response = ChatResponse.fromEvents("session-1", events)

            assertNotNull(response.tokenUsage)
            assertEquals(40, response.tokenUsage!!.totalTokens)
        }

        @Test
        fun `fromEvents returns empty content for empty event list`() {
            val response = ChatResponse.fromEvents("session-1", emptyList())

            assertEquals("", response.content)
            assertNull(response.thinking)
            assertNull(response.tokenUsage)
        }

        @Test
        fun `fromEvents ignores tool events`() {
            val events = listOf<ChatEvent>(
                StreamTextChatEvent("Start", false, null),
                CallToolChatEvent("t1", "search", mapOf(), null),
                ToolResultChatEvent("t1", "search", "result", true, null),
                StreamTextChatEvent("End", true, null),
            )

            val response = ChatResponse.fromEvents("session-1", events)

            assertEquals("StartEnd", response.content)
        }

        @Test
        fun `fromEvents sets thinking to null when empty`() {
            val events = listOf<ChatEvent>(
                StreamTextChatEvent("text only", true, null),
                EndEventChatEvent(),
            )

            val response = ChatResponse.fromEvents("session-1", events)

            assertNull(response.thinking)
        }
    }
}
