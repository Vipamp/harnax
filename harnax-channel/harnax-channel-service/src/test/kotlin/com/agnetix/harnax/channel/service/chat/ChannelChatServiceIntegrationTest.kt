package com.agnetix.harnax.channel.service.chat

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.channel.sdk.adaptor.AgentAdaptor
import com.agnetix.harnax.channel.sdk.adaptor.AgentContext
import com.agnetix.harnax.channel.sdk.adaptor.AgentResponse
import com.agnetix.harnax.channel.sdk.adaptor.AgentStreamEvent
import com.agnetix.harnax.channel.sdk.adaptor.ChannelAdaptor
import com.agnetix.harnax.channel.sdk.config.ChannelSpec
import com.agnetix.harnax.channel.sdk.config.ChannelType
import com.agnetix.harnax.channel.sdk.message.ChannelMessage
import com.agnetix.harnax.channel.sdk.message.MessageRole
import com.agnetix.harnax.channel.sdk.message.MessageType
import com.agnetix.harnax.channel.sdk.service.ChannelChatService
import com.agnetix.harnax.channel.sdk.session.ChannelSessionManager
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times

/**
 * Integration test for ChannelChatService.
 *
 * Tests the complete message processing pipeline:
 * - Batch mode: process() → sendMessage()
 * - Streaming mode: streamProcess() → sendStreamingFragment()
 * - Error handling: onError() → sendMessage()
 * - Session persistence: user message and assistant reply saved
 * - Hooks: onBeforeProcess, onAfterProcess
 */
class ChannelChatServiceIntegrationTest {

    private lateinit var sessionManager: ChannelSessionManager
    private lateinit var chatService: ChannelChatService
    private lateinit var channelAdaptor: ChannelAdaptor
    private lateinit var agentAdaptor: AgentAdaptor
    private lateinit var channel: ChannelSpec

    @BeforeEach
    fun setUp() {
        sessionManager = mock(ChannelSessionManager::class.java)
        chatService = ChannelChatService(sessionManager)
        channelAdaptor = mock(ChannelAdaptor::class.java)
        agentAdaptor = mock(AgentAdaptor::class.java)

        channel = ChannelSpec(
            id = 1L,
            name = "test-channel",
            type = ChannelType.HTTP,
            agentId = 100L,
            callbackKey = "test-key",
            sessionId = "sess-1",
        )

        // Default: session manager returns empty history
        `when`(sessionManager.getHistory(any(), any(), any())).thenReturn(emptyList())
        `when`(sessionManager.toAgentMessages(any())).thenReturn(emptyList())
    }

    private fun buildMessage(content: String = "hello", sessionId: String = "sess-1"): ChannelMessage = ChannelMessage(
        messageId = "msg-1",
        sessionId = sessionId,
        messageType = MessageType.TEXT,
        role = MessageRole.USER,
        content = content,
        channelType = ChannelType.HTTP,
    )

    // ==================== Batch mode ====================

    @Nested
    inner class BatchMode {
        @BeforeEach
        fun setUp() {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(false)
            `when`(channelAdaptor.supportsStreamingOutput()).thenReturn(false)
        }

        @Test
        fun `batch mode sends complete response via sendMessage`() = runBlocking {
            `when`(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "Hello! I'm an AI assistant.", shouldReply = true),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(channelAdaptor).sendMessage(
                eq(channel),
                eq("sess-1"),
                eq("Hello! I'm an AI assistant."),
            )
        }

        @Test
        fun `batch mode sends typing indicator before processing`() = runBlocking {
            `when`(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "Reply", shouldReply = true),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            val ordering = inOrder(channelAdaptor, agentAdaptor)
            ordering.verify(channelAdaptor).sendTypingIndicator(channel, "sess-1")
            ordering.verify(agentAdaptor).process(any())
            ordering.verify(channelAdaptor).sendMessage(eq(channel), eq("sess-1"), eq("Reply"))
        }

        @Test
        fun `batch mode saves user message and assistant reply`() = runBlocking {
            `when`(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "AI reply", shouldReply = true),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(sessionManager, times(2)).addMessage(eq(1L), any())
        }

        @Test
        fun `batch mode skips sending when shouldReply is false`() = runBlocking {
            `when`(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "", shouldReply = false),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(channelAdaptor, never()).sendMessage(any(), any(), any())
        }

        @Test
        fun `batch mode skips sending when response is blank`() = runBlocking {
            `when`(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "   ", shouldReply = true),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(channelAdaptor, never()).sendMessage(any(), any(), any())
        }
    }

    // ==================== Streaming mode ====================

    @Nested
    inner class StreamingMode {
        @BeforeEach
        fun setUp() {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(true)
            `when`(channelAdaptor.supportsStreamingOutput()).thenReturn(true)
            `when`(agentAdaptor.supportsStreaming()).thenReturn(true)
        }

        @Test
        fun `streaming mode sends each text fragment`() = runBlocking {
            `when`(agentAdaptor.streamProcess(any())).thenReturn(
                flow {
                    emit(AgentStreamEvent.TextStreamEvent("Hello ", false))
                    emit(AgentStreamEvent.TextStreamEvent("World", true))
                    emit(AgentStreamEvent.EndStreamEvent(fullContent = "Hello World"))
                },
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(channelAdaptor).sendStreamingFragment(channel, "sess-1", "Hello ", false)
            verify(channelAdaptor).sendStreamingFragment(channel, "sess-1", "World", true)
        }

        @Test
        fun `streaming mode sends typing indicator on thinking event`() = runBlocking {
            `when`(agentAdaptor.streamProcess(any())).thenReturn(
                flow {
                    emit(AgentStreamEvent.ThinkingStreamEvent("Let me think...", false))
                    emit(AgentStreamEvent.TextStreamEvent("The answer is 42.", true))
                    emit(AgentStreamEvent.EndStreamEvent())
                },
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(channelAdaptor).sendTypingIndicator(channel, "sess-1")
            verify(channelAdaptor).sendStreamingFragment(channel, "sess-1", "The answer is 42.", true)
        }

        @Test
        fun `streaming mode sends error message on error event`() = runBlocking {
            `when`(agentAdaptor.streamProcess(any())).thenReturn(
                flow {
                    emit(
                        AgentStreamEvent.ErrorStreamEvent(
                            code = "6001",
                            message = "Agent failed",
                            requestId = "req-abc",
                        ),
                    )
                },
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(channelAdaptor).sendMessage(
                eq(channel),
                eq("sess-1"),
                argThat { contains("6001") && contains("req-abc") && contains("Agent failed") },
            )
        }

        @Test
        fun `streaming mode saves assistant reply after stream completes`() = runBlocking {
            `when`(agentAdaptor.streamProcess(any())).thenReturn(
                flow {
                    emit(AgentStreamEvent.TextStreamEvent("Part1", false))
                    emit(AgentStreamEvent.TextStreamEvent("Part2", true))
                    emit(AgentStreamEvent.EndStreamEvent())
                },
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(sessionManager, times(2)).addMessage(eq(1L), any())
        }
    }

    // ==================== Error handling ====================

    @Nested
    inner class ErrorHandling {
        @Test
        fun `chat handles exception by calling onError hook`() = runBlocking {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(false)
            `when`(agentAdaptor.process(any())).thenThrow(RuntimeException("LLM timeout"))
            `when`(agentAdaptor.onError(any(), any())).thenReturn(
                AgentResponse(content = "[ERROR] Sorry, try again", shouldReply = true),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(agentAdaptor).onError(any(), any())
            verify(channelAdaptor).sendMessage(
                eq(channel),
                eq("sess-1"),
                eq("[ERROR] Sorry, try again"),
            )
        }

        @Test
        fun `chat saves error response to session`() = runBlocking {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(false)
            `when`(agentAdaptor.process(any())).thenThrow(RuntimeException("fail"))
            `when`(agentAdaptor.onError(any(), any())).thenReturn(
                AgentResponse(content = "Error occurred", shouldReply = true),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            // User message + error assistant message = 2
            verify(sessionManager, times(2)).addMessage(eq(1L), any())
        }

        @Test
        fun `chat does not send error when shouldReply is false`() = runBlocking {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(false)
            `when`(agentAdaptor.process(any())).thenThrow(RuntimeException("fail"))
            `when`(agentAdaptor.onError(any(), any())).thenReturn(
                AgentResponse(content = "", shouldReply = false),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(channelAdaptor, never()).sendMessage(any(), any(), any())
        }
    }

    // ==================== AgentRequest passthrough ====================

    @Nested
    inner class AgentRequestPassthrough {
        @Test
        fun `chat passes ChatAgentRequest to context`() = runBlocking {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(false)
            val chatRequest = ChatAgentRequest(sessionId = "sess-1", message = "hello")
            `when`(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "ok", shouldReply = true),
            )

            chatService.chat(
                buildMessage(),
                channel,
                agentAdaptor,
                channelAdaptor,
                agentRequest = chatRequest,
            )

            verify(agentAdaptor).process(
                argThat<AgentContext> { agentRequest == chatRequest },
            )
        }

        @Test
        fun `chat passes CommandAgentRequest to context`() = runBlocking {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(false)
            val cmdRequest = CommandAgentRequest(
                sessionId = "sess-1",
                command = CommandType.CLEAR,
            )
            `when`(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "Cleared", shouldReply = true),
            )

            chatService.chat(
                buildMessage("/clear"),
                channel,
                agentAdaptor,
                channelAdaptor,
                agentRequest = cmdRequest,
            )

            verify(agentAdaptor).process(
                argThat<AgentContext> { agentRequest == cmdRequest },
            )
        }
    }

    // ==================== Hooks ====================

    @Nested
    inner class Hooks {
        @Test
        fun `chat calls onBeforeProcess before processing`() = runBlocking {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(false)
            `when`(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "ok", shouldReply = true),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            val ordering = inOrder(agentAdaptor)
            ordering.verify(agentAdaptor).onBeforeProcess(any())
            ordering.verify(agentAdaptor).process(any())
        }

        @Test
        fun `batch mode calls onAfterProcess after sending reply`() = runBlocking {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(false)
            `when`(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "reply", shouldReply = true),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(agentAdaptor).onAfterProcess(any(), argThat { content == "reply" })
        }

        @Test
        fun `streaming mode calls onAfterProcess after stream ends`() = runBlocking {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(true)
            `when`(agentAdaptor.streamProcess(any())).thenReturn(
                flow {
                    emit(AgentStreamEvent.TextStreamEvent("Done", true))
                    emit(AgentStreamEvent.EndStreamEvent())
                },
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(agentAdaptor).onAfterProcess(any(), argThat { content == "Done" })
        }
    }

    // ==================== Session history ====================

    @Nested
    inner class SessionHistory {
        @Test
        fun `chat reads history before saving user message`() = runBlocking {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(false)
            `when`(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "ok", shouldReply = true),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            val ordering = inOrder(sessionManager)
            ordering.verify(sessionManager).getHistory(eq(1L), eq("sess-1"), any())
            ordering.verify(sessionManager).addMessage(eq(1L), argThat { role == MessageRole.USER })
        }

        @Test
        fun `chat passes history to AgentContext`() = runBlocking {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(false)
            val historyMessages = listOf(
                ChannelMessage(
                    sessionId = "sess-1",
                    content = "previous message",
                    channelType = ChannelType.HTTP,
                    role = MessageRole.USER,
                ),
            )
            `when`(sessionManager.getHistory(eq(1L), eq("sess-1"), any())).thenReturn(historyMessages)
            `when`(sessionManager.toAgentMessages(historyMessages)).thenReturn(
                listOf(com.agnetix.harnax.channel.sdk.message.AgentMessage(role = "user", content = "previous message")),
            )
            `when`(agentAdaptor.process(any())).thenReturn(
                AgentResponse(content = "ok", shouldReply = true),
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(agentAdaptor).process(
                argThat<AgentContext> { history.size == 1 && history[0].content == "previous message" },
            )
        }

        @Test
        fun `streaming mode merges fragments into fullContent for session save`() = runBlocking {
            `when`(channelAdaptor.shouldUseStreaming(any())).thenReturn(true)
            `when`(agentAdaptor.streamProcess(any())).thenReturn(
                flow {
                    emit(AgentStreamEvent.TextStreamEvent("Hello ", false))
                    emit(AgentStreamEvent.TextStreamEvent("World", false))
                    emit(AgentStreamEvent.TextStreamEvent("!", true))
                    emit(AgentStreamEvent.EndStreamEvent())
                },
            )

            chatService.chat(buildMessage(), channel, agentAdaptor, channelAdaptor)

            verify(sessionManager).addMessage(
                eq(1L),
                argThat { role == MessageRole.ASSISTANT && content == "Hello World!" },
            )
        }
    }
}
