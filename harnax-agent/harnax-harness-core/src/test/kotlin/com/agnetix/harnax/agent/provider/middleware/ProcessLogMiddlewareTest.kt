package com.agnetix.harnax.agent.provider.middleware

import com.agnetix.harnax.agent.adaptor.ProcessLog
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.AgentEventType
import io.agentscope.core.event.ToolCallStartEvent
import io.agentscope.core.event.ToolResultEndEvent
import io.agentscope.core.event.ToolResultTextDeltaEvent
import io.agentscope.core.message.ToolResultState
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.middleware.ActingInput
import io.agentscope.core.middleware.AgentInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.util.function.Function

class ProcessLogMiddlewareTest {

    private lateinit var middleware: ProcessLogMiddleware
    private lateinit var adaptor: ProcessLogAdaptor
    private lateinit var agent: Agent
    private lateinit var runtimeContext: RuntimeContext
    private val capturedLogs = mutableListOf<ProcessLog>()

    @BeforeEach
    fun setUp() {
        middleware = ProcessLogMiddleware()
        adaptor = ProcessLogAdaptor { log -> capturedLogs.add(log) }
        agent = mock(Agent::class.java)
        runtimeContext = mock(RuntimeContext::class.java)

        `when`(agent.name).thenReturn("TestAgent")

        middleware.initial(adaptor, 1L, "TestAgent", "session-123")
    }

    // ==================== onAgent ====================

    @Nested
    inner class OnAgent {
        @Test
        fun `onAgent logs agent start message`() {
            val input = mock(AgentInput::class.java)
            val next = Function<AgentInput, Flux<AgentEvent>> { Flux.empty() }

            StepVerifier.create(middleware.onAgent(agent, runtimeContext, input, next))
                .verifyComplete()

            assertTrue(capturedLogs.any { it.message.contains("Agent 'TestAgent' calling") })
        }

        @Test
        fun `onAgent logs agent end event`() {
            val input = mock(AgentInput::class.java)
            val endEvent = mock(AgentEvent::class.java)
            `when`(endEvent.type).thenReturn(AgentEventType.AGENT_END)

            val next = Function<AgentInput, Flux<AgentEvent>> { Flux.just(endEvent) }

            StepVerifier.create(middleware.onAgent(agent, runtimeContext, input, next))
                .expectNextCount(1)
                .verifyComplete()

            assertTrue(capturedLogs.any { it.message.contains("Agent execution completed") })
        }

        @Test
        fun `onAgent logs tool call start event`() {
            val input = mock(AgentInput::class.java)
            val toolCallEvent = mock(ToolCallStartEvent::class.java)
            `when`(toolCallEvent.type).thenReturn(AgentEventType.TOOL_CALL_START)
            `when`(toolCallEvent.toolCallName).thenReturn("search_web")

            val next = Function<AgentInput, Flux<AgentEvent>> { Flux.just(toolCallEvent) }

            StepVerifier.create(middleware.onAgent(agent, runtimeContext, input, next))
                .expectNextCount(1)
                .verifyComplete()

            assertTrue(capturedLogs.any { it.message.contains("Call tool: 'search_web'") })
        }

        @Test
        fun `onAgent logs tool result end event`() {
            val input = mock(AgentInput::class.java)
            val toolResultEvent = mock(ToolResultEndEvent::class.java)
            `when`(toolResultEvent.type).thenReturn(AgentEventType.TOOL_RESULT_END)
            `when`(toolResultEvent.toolCallName).thenReturn("search_web")
            `when`(toolResultEvent.state).thenReturn(ToolResultState.SUCCESS)

            val next = Function<AgentInput, Flux<AgentEvent>> { Flux.just(toolResultEvent) }

            StepVerifier.create(middleware.onAgent(agent, runtimeContext, input, next))
                .expectNextCount(1)
                .verifyComplete()

            assertTrue(
                capturedLogs.any {
                    it.message.contains("Tool 'search_web' completed") && it.message.contains("state=SUCCESS")
                },
            )
        }

        @Test
        fun `onAgent logs tool result text delta with non-empty output`() {
            val input = mock(AgentInput::class.java)
            val deltaEvent = mock(ToolResultTextDeltaEvent::class.java)
            `when`(deltaEvent.type).thenReturn(AgentEventType.TOOL_RESULT_TEXT_DELTA)
            `when`(deltaEvent.toolCallName).thenReturn("code_exec")
            `when`(deltaEvent.delta).thenReturn("Hello World")

            val next = Function<AgentInput, Flux<AgentEvent>> { Flux.just(deltaEvent) }

            StepVerifier.create(middleware.onAgent(agent, runtimeContext, input, next))
                .expectNextCount(1)
                .verifyComplete()

            assertTrue(
                capturedLogs.any {
                    it.message.contains("Tool 'code_exec' output: 'Hello World'")
                },
            )
        }

        @Test
        fun `onAgent skips tool result text delta with empty output`() {
            val input = mock(AgentInput::class.java)
            val deltaEvent = mock(ToolResultTextDeltaEvent::class.java)
            `when`(deltaEvent.type).thenReturn(AgentEventType.TOOL_RESULT_TEXT_DELTA)
            `when`(deltaEvent.toolCallName).thenReturn("code_exec")
            `when`(deltaEvent.delta).thenReturn("")

            val next = Function<AgentInput, Flux<AgentEvent>> { Flux.just(deltaEvent) }

            StepVerifier.create(middleware.onAgent(agent, runtimeContext, input, next))
                .expectNextCount(1)
                .verifyComplete()

            assertFalse(capturedLogs.any { it.message.contains("output:") })
        }

        @Test
        fun `onAgent logs error on stream failure`() {
            val input = mock(AgentInput::class.java)
            val error = RuntimeException("Stream failed")
            val next = Function<AgentInput, Flux<AgentEvent>> { Flux.error(error) }

            StepVerifier.create(middleware.onAgent(agent, runtimeContext, input, next))
                .expectError(RuntimeException::class.java)
                .verify()

            assertTrue(capturedLogs.any { it.message.contains("Error") && it.throwable == error })
        }

        @Test
        fun `onAgent passes through all events from next`() {
            val input = mock(AgentInput::class.java)
            val event1 = mock(AgentEvent::class.java)
            val event2 = mock(AgentEvent::class.java)
            `when`(event1.type).thenReturn(AgentEventType.TEXT_BLOCK_DELTA)
            `when`(event2.type).thenReturn(AgentEventType.TEXT_BLOCK_DELTA)

            val next = Function<AgentInput, Flux<AgentEvent>> { Flux.just(event1, event2) }

            StepVerifier.create(middleware.onAgent(agent, runtimeContext, input, next))
                .expectNextCount(2)
                .verifyComplete()
        }
    }

    // ==================== onActing ====================

    @Nested
    inner class OnActing {
        @Test
        fun `onActing logs each tool call with name and input`() {
            val input = mock(ActingInput::class.java)
            val toolCall1 = ToolUseBlock("t1", "search", mapOf("query" to "test"))
            val toolCall2 = ToolUseBlock("t2", "calculate", mapOf("expr" to "1+1"))
            `when`(input.toolCalls).thenReturn(listOf(toolCall1, toolCall2))

            val next = Function<ActingInput, Flux<AgentEvent>> { Flux.empty() }

            StepVerifier.create(middleware.onActing(agent, runtimeContext, input, next))
                .verifyComplete()

            assertTrue(
                capturedLogs.any {
                    it.message.contains("Call tool: 'search'") && it.message.contains("{query=test}")
                },
            )
            assertTrue(
                capturedLogs.any {
                    it.message.contains("Call tool: 'calculate'") && it.message.contains("{expr=1+1}")
                },
            )
        }

        @Test
        fun `onActing passes through events from next`() {
            val input = mock(ActingInput::class.java)
            `when`(input.toolCalls).thenReturn(emptyList())

            val event = mock(AgentEvent::class.java)
            val next = Function<ActingInput, Flux<AgentEvent>> { Flux.just(event) }

            StepVerifier.create(middleware.onActing(agent, runtimeContext, input, next))
                .expectNextCount(1)
                .verifyComplete()
        }

        @Test
        fun `onActing handles empty tool calls list`() {
            val input = mock(ActingInput::class.java)
            `when`(input.toolCalls).thenReturn(emptyList())

            val next = Function<ActingInput, Flux<AgentEvent>> { Flux.empty() }

            StepVerifier.create(middleware.onActing(agent, runtimeContext, input, next))
                .verifyComplete()

            assertTrue(capturedLogs.isEmpty())
        }
    }
}
