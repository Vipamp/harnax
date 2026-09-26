package com.agnetix.harnax.agent.provider.middleware

import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.token.TokenStat
import com.agnetix.harnax.agent.adaptor.token.TokenStatBuilder
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.ModelCallEndEvent
import io.agentscope.core.event.ModelCallStartEvent
import io.agentscope.core.middleware.ModelCallInput
import io.agentscope.core.model.ChatUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.util.function.Function

/**
 * What one model call costs, and where that gets written (AGENT-28).
 *
 * The middleware is the single recorder for both call paths, so these cases are the contract the batch
 * path used to miss: a row per model call, carrying the run's attribution and the call's own moment.
 */
class TokenStatsMiddlewareTest {

    private val rows = mutableListOf<TokenStat>()

    private fun middleware(
        seed: TokenStatBuilder = TokenStatBuilder().agentId(12L).tenantId(3L).sessionId("web-12").modelId(100L),
    ): TokenStatsMiddleware = TokenStatsMiddleware(TokenStatAdaptor { rows.add(it) }, seed)

    private fun call(
        middleware: TokenStatsMiddleware,
        vararg events: AgentEvent,
    ) {
        val agent = mock(Agent::class.java)
        `when`(agent.name).thenReturn("Researcher")
        val next = Function<ModelCallInput, Flux<AgentEvent>> { Flux.just(*events) }
        StepVerifier.create(middleware.onModelCall(agent, mock(RuntimeContext::class.java), mock(ModelCallInput::class.java), next))
            .expectNextCount(events.size.toLong())
            .verifyComplete()
    }

    private fun endEvent(input: Int, output: Int): ModelCallEndEvent = ModelCallEndEvent("reply-1", ChatUsage.builder().inputTokens(input).outputTokens(output).time(0.5).build())

    @Test
    @DisplayName("each model call of a turn is its own row, with the run's attribution")
    fun recordsOneRowPerModelCall() {
        call(middleware(), ModelCallStartEvent("reply-1"), endEvent(120, 30), endEvent(400, 70))

        assertEquals(2, rows.size, "a turn that called the model twice must not collapse into one row")
        assertEquals(listOf(120 to 30, 400 to 70), rows.map { it.inputToken to it.outputToken })
        assertEquals(listOf(150, 470), rows.map { it.totalToken })
        assertEquals(listOf(12L, 12L), rows.map { it.agentId })
        assertEquals(listOf(3L, 3L), rows.map { it.tenantId })
        assertEquals(listOf(100L, 100L), rows.map { it.modelId })
        assertEquals(listOf("web-12", "web-12"), rows.map { it.sessionId })
    }

    @Test
    @DisplayName("a model call that reported no usage is no row")
    fun skipsCallWithoutUsage() {
        call(middleware(), ModelCallEndEvent("reply-1", null))

        assertEquals(emptyList<TokenStat>(), rows)
    }

    @Test
    @DisplayName("a row is stamped with its own call, not with the agent build")
    fun stampsTheCallNotTheSeed() {
        // The seed is built once per agent and a keep-alive wrapper serves turn after turn with it, so a
        // row that inherited the seed's timestamp would pile a session's cost into one time-series bucket.
        val seed = TokenStatBuilder().agentId(12L).sessionId("web-12").modelId(100L).timestamp(1_600_000_000_000L)
        val before = System.currentTimeMillis()
        call(middleware(seed), endEvent(10, 5))

        assertEquals(1, rows.size)
        assertEquals(true, rows.single().timestamp >= before, "expected the call time, got ${rows.single().timestamp}")
    }

    @Test
    @DisplayName("a failed write costs the row and not the answer")
    fun writeFailureKeepsTheStreamAlive() {
        val broken = TokenStatsMiddleware(
            TokenStatAdaptor { throw IllegalStateException("token_stats is unreachable") },
            TokenStatBuilder().agentId(12L).sessionId("web-12").modelId(100L),
        )
        val event = endEvent(10, 5)

        StepVerifier.create(
            broken.onModelCall(
                mock(Agent::class.java).also { `when`(it.name).thenReturn("Researcher") },
                mock(RuntimeContext::class.java),
                mock(ModelCallInput::class.java),
            ) { Flux.just(event) },
        )
            .expectNext(event)
            .verifyComplete()
    }
}
