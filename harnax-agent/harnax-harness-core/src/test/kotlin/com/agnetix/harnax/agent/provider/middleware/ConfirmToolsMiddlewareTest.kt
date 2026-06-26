package com.agnetix.harnax.agent.provider.middleware

import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.middleware.ReasoningInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.util.function.Function

class ConfirmToolsMiddlewareTest {

    private lateinit var middleware: ConfirmToolsMiddleware
    private lateinit var agent: Agent
    private lateinit var runtimeContext: RuntimeContext

    @BeforeEach
    fun setUp() {
        middleware = ConfirmToolsMiddleware()
        agent = mock(Agent::class.java)
        runtimeContext = mock(RuntimeContext::class.java)
    }

    @Test
    fun `onReasoning passes through events from next`() {
        val input = mock(ReasoningInput::class.java)
        val event1 = mock(AgentEvent::class.java)
        val event2 = mock(AgentEvent::class.java)

        val next = Function<ReasoningInput, Flux<AgentEvent>> { Flux.just(event1, event2) }

        StepVerifier.create(middleware.onReasoning(agent, runtimeContext, input, next))
            .expectNextCount(2)
            .verifyComplete()
    }

    @Test
    fun `onReasoning passes through empty flux`() {
        val input = mock(ReasoningInput::class.java)
        val next = Function<ReasoningInput, Flux<AgentEvent>> { Flux.empty() }

        StepVerifier.create(middleware.onReasoning(agent, runtimeContext, input, next))
            .verifyComplete()
    }

    @Test
    fun `onReasoning passes through error from next`() {
        val input = mock(ReasoningInput::class.java)
        val error = RuntimeException("reasoning failed")
        val next = Function<ReasoningInput, Flux<AgentEvent>> { Flux.error(error) }

        StepVerifier.create(middleware.onReasoning(agent, runtimeContext, input, next))
            .expectError(RuntimeException::class.java)
            .verify()
    }

    @Test
    fun `setDangerousTools adds tools to internal set`() {
        val tools = mutableSetOf("delete_file", "exec_command")
        middleware.setDangerousTools(tools)

        // Verify the middleware still passes through after setting dangerous tools
        val input = mock(ReasoningInput::class.java)
        val event = mock(AgentEvent::class.java)
        val next = Function<ReasoningInput, Flux<AgentEvent>> { Flux.just(event) }

        StepVerifier.create(middleware.onReasoning(agent, runtimeContext, input, next))
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `setDangerousTools can be called multiple times`() {
        middleware.setDangerousTools(mutableSetOf("tool_a"))
        middleware.setDangerousTools(mutableSetOf("tool_b"))

        val input = mock(ReasoningInput::class.java)
        val next = Function<ReasoningInput, Flux<AgentEvent>> { Flux.empty() }

        StepVerifier.create(middleware.onReasoning(agent, runtimeContext, input, next))
            .verifyComplete()
    }

    @Test
    fun `onReasoning calls next with the same input`() {
        val input = mock(ReasoningInput::class.java)
        val capturedInput = mutableListOf<ReasoningInput>()

        val next = Function<ReasoningInput, Flux<AgentEvent>> {
            capturedInput.add(it)
            Flux.empty()
        }

        middleware.onReasoning(agent, runtimeContext, input, next)

        assertEquals(1, capturedInput.size)
        assertSame(input, capturedInput[0])
    }
}
