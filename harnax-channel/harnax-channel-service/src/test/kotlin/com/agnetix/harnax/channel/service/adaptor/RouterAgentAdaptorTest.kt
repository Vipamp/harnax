package com.agnetix.harnax.channel.service.adaptor

import com.agnetix.harnax.agent.protocol.*
import com.agnetix.harnax.channel.sdk.adaptor.AgentContext
import com.agnetix.harnax.channel.sdk.adaptor.AgentStreamEvent
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.service.client.RouterClient
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any

class RouterAgentAdaptorTest {

    private lateinit var routerClient: RouterClient
    private lateinit var adaptor: RouterAgentAdaptor

    @BeforeEach
    fun setUp() {
        routerClient = mock(RouterClient::class.java)
        adaptor = RouterAgentAdaptor(routerClient)
    }

    private fun buildContext(
        sessionId: String = "session-1",
        agentId: Long = 1L,
        content: String = "hello",
        agentRequest: AgentRequest? = null,
        requestId: String = "req-1",
    ): AgentContext {
        val channelSpec = ChannelSpec(
            id = 1L,
            name = "test-channel",
            type = ChannelType.HTTP,
            agentId = agentId,
            callbackKey = "test-key",
            sessionId = sessionId,
        )
        val message = ChannelMessage(
            content = content,
            sessionId = sessionId,
            channelType = ChannelType.HTTP,
        )
        return AgentContext(
            message = message,
            channelSpec = channelSpec,
            agentRequest = agentRequest,
            requestId = requestId,
        )
    }

    // ==================== getName ====================

    @Test
    fun `getName returns router-agent-proxy`() {
        assertEquals("router-agent-proxy", adaptor.getName())
    }

    // ==================== supportsStreaming ====================

    @Test
    fun `supportsStreaming returns true`() {
        assertTrue(adaptor.supportsStreaming())
    }

    // ==================== process (batch) ====================

    @Nested
    inner class Process {
        @Test
        fun `process sends ChatAgentRequest to router and returns response`() = runBlocking {
            val chatResponse = ChatResponse(sessionId = "session-1", content = "AI reply")
            `when`(
                routerClient.sendToAgent(
                    sessionId = "session-1",
                    agentId = 1L,
                    message = "hello",
                    imageUrls = emptyList(),
                ),
            ).thenReturn(chatResponse)

            val context = buildContext(
                agentRequest = ChatAgentRequest(sessionId = "session-1", message = "hello"),
            )

            val response = adaptor.process(context)

            assertEquals("AI reply", response.content)
            assertTrue(response.shouldReply)
        }

        @Test
        fun `process sends CommandAgentRequest to router`() = runBlocking {
            val commandResponse = CommandResponse.success("session-1", message = "Cleared")
            `when`(
                routerClient.sendCommand(
                    sessionId = "session-1",
                    agentId = 1L,
                    command = CommandType.CLEAR,
                    args = "",
                ),
            ).thenReturn(commandResponse)

            val context = buildContext(
                agentRequest = CommandAgentRequest(
                    sessionId = "session-1",
                    command = CommandType.CLEAR,
                ),
            )

            val response = adaptor.process(context)

            assertEquals("Cleared", response.content)
            assertTrue(response.shouldReply)
        }

        @Test
        fun `process falls back to message content when no agentRequest`() = runBlocking {
            val chatResponse = ChatResponse(sessionId = "session-1", content = "Fallback reply")
            `when`(
                routerClient.sendToAgent(
                    sessionId = "session-1",
                    agentId = 1L,
                    message = "hello from channel",
                    imageUrls = emptyList(),
                ),
            ).thenReturn(chatResponse)

            val context = buildContext(content = "hello from channel")

            val response = adaptor.process(context)

            assertEquals("Fallback reply", response.content)
        }

        @Test
        fun `process handles CommandResponse with null message`() = runBlocking {
            val commandResponse = CommandResponse(sessionId = "session-1", success = true, message = null)
            `when`(
                routerClient.sendCommand(
                    sessionId = "session-1",
                    agentId = 1L,
                    command = CommandType.INTERRUPT,
                    args = "",
                ),
            ).thenReturn(commandResponse)

            val context = buildContext(
                agentRequest = CommandAgentRequest(
                    sessionId = "session-1",
                    command = CommandType.INTERRUPT,
                ),
            )

            val response = adaptor.process(context)

            assertEquals("Command executed", response.content)
        }
    }

    // ==================== streamProcess ====================

    @Nested
    inner class StreamProcess {
        @Test
        fun `streamProcess converts ChatEvents to AgentStreamEvents`() = runBlocking {
            val events: List<ChatEvent> = listOf(
                StreamTextChatEvent("Hello", false, null),
                StreamTextChatEvent(" World", true, null),
                EndEventChatEvent(),
            )
            `when`(routerClient.streamRequest(any(), any())).thenReturn(flow { events.forEach { emit(it) } })

            val context = buildContext(
                agentRequest = ChatAgentRequest(sessionId = "session-1", message = "hello"),
            )

            val results = adaptor.streamProcess(context).toList()

            assertEquals(3, results.size)
            assertTrue(results[0] is AgentStreamEvent.TextStreamEvent)
            assertEquals("Hello", (results[0] as AgentStreamEvent.TextStreamEvent).content)
            assertEquals(" World", (results[1] as AgentStreamEvent.TextStreamEvent).content)
            assertTrue(results[2] is AgentStreamEvent.EndStreamEvent)
        }

        @Test
        fun `streamProcess converts thinking events`() = runBlocking {
            val events: List<ChatEvent> = listOf(
                StreamThinkingChatEvent("Thinking...", false, null),
                EndEventChatEvent(),
            )
            `when`(routerClient.streamRequest(any(), any())).thenReturn(flow { events.forEach { emit(it) } })

            val context = buildContext(
                agentRequest = ChatAgentRequest(sessionId = "session-1", message = "think"),
            )

            val results = adaptor.streamProcess(context).toList()

            assertEquals(2, results.size)
            assertTrue(results[0] is AgentStreamEvent.ThinkingStreamEvent)
            assertEquals("Thinking...", (results[0] as AgentStreamEvent.ThinkingStreamEvent).content)
        }

        @Test
        fun `streamProcess converts error events with requestId`() = runBlocking {
            val events: List<ChatEvent> = listOf(
                ErrorChatEvent(code = "6001", message = "Agent failed"),
                EndEventChatEvent(),
            )
            `when`(routerClient.streamRequest(any(), any())).thenReturn(flow { events.forEach { emit(it) } })

            val context = buildContext(
                agentRequest = ChatAgentRequest(sessionId = "session-1", message = "error"),
                requestId = "req-123",
            )

            val results = adaptor.streamProcess(context).toList()

            assertEquals(2, results.size)
            assertTrue(results[0] is AgentStreamEvent.ErrorStreamEvent)
            val errorEvent = results[0] as AgentStreamEvent.ErrorStreamEvent
            assertEquals("6001", errorEvent.code)
            assertEquals("Agent failed", errorEvent.message)
            assertEquals("req-123", errorEvent.requestId)
        }

        @Test
        fun `streamProcess skips tool events`() = runBlocking {
            val events: List<ChatEvent> = listOf(
                StreamTextChatEvent("Start", false, null),
                CallToolChatEvent("tool-1", "search", mapOf("q" to "test"), null),
                ToolResultChatEvent("tool-1", "search", "result", true, null),
                StreamTextChatEvent("End", true, null),
                EndEventChatEvent(),
            )
            `when`(routerClient.streamRequest(any(), any())).thenReturn(flow { events.forEach { emit(it) } })

            val context = buildContext(
                agentRequest = ChatAgentRequest(sessionId = "session-1", message = "tools"),
            )

            val results = adaptor.streamProcess(context).toList()

            assertEquals(3, results.size)
            assertTrue(results[0] is AgentStreamEvent.TextStreamEvent)
            assertTrue(results[1] is AgentStreamEvent.TextStreamEvent)
            assertTrue(results[2] is AgentStreamEvent.EndStreamEvent)
        }

        @Test
        fun `streamProcess falls back to message content when no agentRequest`() = runBlocking {
            `when`(routerClient.streamRequest(any(), any())).thenReturn(
                flow {
                    emit(StreamTextChatEvent("ok", true, null))
                    emit(EndEventChatEvent())
                },
            )

            val context = buildContext(content = "raw message")

            val results = adaptor.streamProcess(context).toList()

            assertEquals(2, results.size)
        }

        @Test
        fun `streamProcess handles empty event flow`() = runBlocking {
            `when`(routerClient.streamRequest(any(), any())).thenReturn(flow { })

            val context = buildContext(
                agentRequest = ChatAgentRequest(sessionId = "session-1", message = "empty"),
            )

            val results = adaptor.streamProcess(context).toList()

            assertTrue(results.isEmpty())
        }

        @Test
        fun `streamProcess skips ToolConfirmChatEvent and ToolResultChatEvent`() = runBlocking {
            val events: List<ChatEvent> = listOf(
                StreamTextChatEvent("before", false, null),
                ToolConfirmChatEvent(
                    pendingCallTools = listOf(
                        PendingCallTool("t1", "delete", mapOf("file" to "a.txt"), true),
                    ),
                    tokenUsage = null,
                ),
                ToolResultChatEvent("t1", "delete", "done", true, null),
                StreamTextChatEvent("after", true, null),
                EndEventChatEvent(),
            )
            `when`(routerClient.streamRequest(any(), any())).thenReturn(flow { events.forEach { emit(it) } })

            val context = buildContext(
                agentRequest = ChatAgentRequest(sessionId = "session-1", message = "tools"),
            )

            val results = adaptor.streamProcess(context).toList()

            // Only TextStreamEvent x2 + EndStreamEvent = 3, tool events are skipped
            assertEquals(3, results.size)
            assertTrue(results[0] is AgentStreamEvent.TextStreamEvent)
            assertEquals("before", (results[0] as AgentStreamEvent.TextStreamEvent).content)
            assertTrue(results[1] is AgentStreamEvent.TextStreamEvent)
            assertEquals("after", (results[1] as AgentStreamEvent.TextStreamEvent).content)
            assertTrue(results[2] is AgentStreamEvent.EndStreamEvent)
        }

        @Test
        fun `streamProcess handles only EndEvent`() = runBlocking {
            `when`(routerClient.streamRequest(any(), any())).thenReturn(
                flow {
                    emit(EndEventChatEvent())
                },
            )

            val context = buildContext(
                agentRequest = ChatAgentRequest(sessionId = "session-1", message = "end-only"),
            )

            val results = adaptor.streamProcess(context).toList()

            assertEquals(1, results.size)
            assertTrue(results[0] is AgentStreamEvent.EndStreamEvent)
        }
    }
}
