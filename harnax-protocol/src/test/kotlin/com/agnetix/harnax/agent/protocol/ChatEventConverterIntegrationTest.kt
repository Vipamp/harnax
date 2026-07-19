package com.agnetix.harnax.agent.protocol

import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.AgentEventType
import io.agentscope.core.event.ExceedMaxItersEvent
import io.agentscope.core.event.ModelCallEndEvent
import io.agentscope.core.event.TextBlockDeltaEvent
import io.agentscope.core.event.ThinkingBlockDeltaEvent
import io.agentscope.core.event.ToolCallStartEvent
import io.agentscope.core.event.ToolResultEndEvent
import io.agentscope.core.model.ChatUsage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import reactor.test.StepVerifier

/**
 * Integration test for ChatEventConverter.
 *
 * Tests the conversion pipeline from agentscope AgentEvent types
 * to harnax ChatEvent types, verifying correct type mapping,
 * field extraction, and edge case handling.
 */
class ChatEventConverterIntegrationTest {

    private val emptyDangerousTools: Set<String> = emptySet()

    // ==================== Text events ====================

    @Nested
    inner class TextEvents {
        @Test
        fun `TEXT_BLOCK_DELTA converts to StreamTextChatEvent with delta content`() {
            val event = mock(TextBlockDeltaEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.TEXT_BLOCK_DELTA)
            `when`(event.delta).thenReturn("Hello World")

            val result = ChatEventConverter.convert(event, emptyDangerousTools)

            StepVerifier.create(result)
                .assertNext { chatEvent ->
                    assertTrue(chatEvent is StreamTextChatEvent)
                    val textEvent = chatEvent as StreamTextChatEvent
                    assertEquals("Hello World", textEvent.message)
                    assertFalse(textEvent.isLast)
                }
                .verifyComplete()
        }

        @Test
        fun `TEXT_BLOCK_END converts to StreamTextChatEvent with isLast true`() {
            val event = mock(AgentEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.TEXT_BLOCK_END)

            val result = ChatEventConverter.convert(event, emptyDangerousTools)

            StepVerifier.create(result)
                .assertNext { chatEvent ->
                    assertTrue(chatEvent is StreamTextChatEvent)
                    val textEvent = chatEvent as StreamTextChatEvent
                    assertEquals("", textEvent.message)
                    assertTrue(textEvent.isLast)
                }
                .verifyComplete()
        }

        @Test
        fun `TEXT_BLOCK_DELTA with null delta converts to empty message`() {
            val event = mock(TextBlockDeltaEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.TEXT_BLOCK_DELTA)
            `when`(event.delta).thenReturn(null)

            val result = ChatEventConverter.convert(event, emptyDangerousTools)

            StepVerifier.create(result)
                .assertNext { chatEvent ->
                    val textEvent = chatEvent as StreamTextChatEvent
                    assertEquals("", textEvent.message)
                }
                .verifyComplete()
        }
    }

    // ==================== Thinking events ====================

    @Nested
    inner class ThinkingEvents {
        @Test
        fun `THINKING_BLOCK_DELTA converts to StreamThinkingChatEvent`() {
            val event = mock(ThinkingBlockDeltaEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.THINKING_BLOCK_DELTA)
            `when`(event.delta).thenReturn("Let me analyze this...")

            val result = ChatEventConverter.convert(event, emptyDangerousTools)

            StepVerifier.create(result)
                .assertNext { chatEvent ->
                    assertTrue(chatEvent is StreamThinkingChatEvent)
                    val thinkingEvent = chatEvent as StreamThinkingChatEvent
                    assertEquals("Let me analyze this...", thinkingEvent.message)
                    assertFalse(thinkingEvent.isLast)
                }
                .verifyComplete()
        }

        @Test
        fun `THINKING_BLOCK_END converts to StreamThinkingChatEvent with isLast true`() {
            val event = mock(AgentEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.THINKING_BLOCK_END)

            val result = ChatEventConverter.convert(event, emptyDangerousTools)

            StepVerifier.create(result)
                .assertNext { chatEvent ->
                    assertTrue(chatEvent is StreamThinkingChatEvent)
                    assertTrue((chatEvent as StreamThinkingChatEvent).isLast)
                }
                .verifyComplete()
        }
    }

    // ==================== Tool events ====================

    @Nested
    inner class ToolEvents {
        @Test
        fun `TOOL_CALL_START returns empty (handled by Wrapper)`() {
            val event = mock(ToolCallStartEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.TOOL_CALL_START)

            val result = ChatEventConverter.convert(event, emptyDangerousTools)

            StepVerifier.create(result)
                .verifyComplete()
        }

        @Test
        fun `convertToolCallEnd emits CallToolChatEvent with accumulated args`() {
            val result = ChatEventConverter.convertToolCallEnd(
                "call_123",
                "search_web",
                """{"query":"kotlin"}""",
            )

            StepVerifier.create(result)
                .assertNext { chatEvent ->
                    assertTrue(chatEvent is CallToolChatEvent)
                    val toolEvent = chatEvent as CallToolChatEvent
                    assertEquals("call_123", toolEvent.toolId)
                    assertEquals("search_web", toolEvent.toolName)
                    assertEquals("kotlin", toolEvent.arguments["query"])
                }
                .verifyComplete()
        }

        @Test
        fun `TOOL_RESULT_END converts to ToolResultChatEvent`() {
            val event = mock(ToolResultEndEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.TOOL_RESULT_END)
            `when`(event.toolCallId).thenReturn("call_456")
            `when`(event.toolCallName).thenReturn("code_exec")

            val result = ChatEventConverter.convertToolResultEnd(event, "output text")

            StepVerifier.create(result)
                .assertNext { chatEvent ->
                    assertTrue(chatEvent is ToolResultChatEvent)
                    val resultEvent = chatEvent as ToolResultChatEvent
                    assertEquals("call_456", resultEvent.toolId)
                    assertEquals("code_exec", resultEvent.toolName)
                    assertTrue(resultEvent.success)
                    assertEquals("output text", resultEvent.message)
                }
                .verifyComplete()
        }

        @Test
        fun `EXCEED_MAX_ITERS converts to warning StreamTextChatEvent`() {
            val event = mock(ExceedMaxItersEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.EXCEED_MAX_ITERS)
            `when`(event.maxIters).thenReturn(50)
            `when`(event.currentIter).thenReturn(51)

            val result = ChatEventConverter.convert(event, emptyDangerousTools)

            StepVerifier.create(result)
                .assertNext { chatEvent ->
                    assertTrue(chatEvent is StreamTextChatEvent)
                    val textEvent = chatEvent as StreamTextChatEvent
                    assertTrue(textEvent.message.contains("50"))
                }
                .verifyComplete()
        }
    }

    // ==================== Model call end ====================

    @Nested
    inner class ModelCallEnd {
        @Test
        fun `MODEL_CALL_END with usage converts to StreamTextChatEvent with token usage`() {
            val usage = mock(ChatUsage::class.java)
            `when`(usage.inputTokens).thenReturn(100)
            `when`(usage.outputTokens).thenReturn(50)
            `when`(usage.totalTokens).thenReturn(150)
            `when`(usage.time).thenReturn(2.5)

            val event = mock(ModelCallEndEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.MODEL_CALL_END)
            `when`(event.usage).thenReturn(usage)

            val result = ChatEventConverter.convert(event, emptyDangerousTools)

            StepVerifier.create(result)
                .assertNext { chatEvent ->
                    assertTrue(chatEvent is StreamTextChatEvent)
                    assertNotNull(chatEvent.tokenUsage)
                    assertEquals(100, chatEvent.tokenUsage!!.inputTokens)
                    assertEquals(50, chatEvent.tokenUsage!!.outputTokens)
                    assertEquals(150, chatEvent.tokenUsage!!.totalTokens)
                }
                .verifyComplete()
        }

        @Test
        fun `MODEL_CALL_END without usage returns empty flux`() {
            val event = mock(ModelCallEndEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.MODEL_CALL_END)
            `when`(event.usage).thenReturn(null)

            val result = ChatEventConverter.convert(event, emptyDangerousTools)

            StepVerifier.create(result)
                .verifyComplete()
        }

        @Test
        fun `MODEL_CALL_END with exception during usage extraction returns empty flux`() {
            val event = mock(ModelCallEndEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.MODEL_CALL_END)
            `when`(event.usage).thenThrow(RuntimeException("usage not available"))

            val result = ChatEventConverter.convert(event, emptyDangerousTools)

            StepVerifier.create(result)
                .verifyComplete()
        }
    }

    // ==================== Unknown events ====================

    @Nested
    inner class UnknownEvents {
        @Test
        fun `unknown event type returns empty flux`() {
            val event = mock(AgentEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.AGENT_START)

            val result = ChatEventConverter.convert(event, emptyDangerousTools)

            StepVerifier.create(result)
                .verifyComplete()
        }

        @Test
        fun `AGENT_END returns empty flux`() {
            val event = mock(AgentEvent::class.java)
            `when`(event.type).thenReturn(AgentEventType.AGENT_END)

            val result = ChatEventConverter.convert(event, emptyDangerousTools)

            StepVerifier.create(result)
                .verifyComplete()
        }
    }

    // ==================== convertInput ====================

    @Nested
    inner class ConvertInput {
        @Test
        fun `convertInput handles null`() {
            val result = ChatEventConverter.convertInput(null)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `convertInput handles Map input`() {
            val input = mapOf("query" to "test", "limit" to 10)
            val result = ChatEventConverter.convertInput(input)

            assertEquals("test", result["query"])
            assertEquals(10, result["limit"])
        }

        @Test
        fun `convertInput handles String input`() {
            val result = ChatEventConverter.convertInput("raw string")

            assertEquals("raw string", result["value"])
        }

        @Test
        fun `convertInput handles Map with null values`() {
            val input = mapOf<String, Any?>("key" to null)
            val result = ChatEventConverter.convertInput(input)

            assertEquals("", result["key"])
        }
    }
}
