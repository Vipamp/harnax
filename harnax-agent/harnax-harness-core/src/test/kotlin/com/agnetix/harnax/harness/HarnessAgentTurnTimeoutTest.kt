package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.common.error.HarnaxErrorCode
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.harness.agent.HarnessAgent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * The turn budget being enforced, not just chosen.
 *
 * [HarnessAgentTurnBudgetTest] pins which number a wrapper is built with — a team turn gets
 * `harnax.team.turn-timeout-seconds`, a lone agent gets `harnax.turn-timeout-seconds`, and a non-positive
 * value means no budget. What it cannot see is whether the wrapper then acts on the number it holds, and
 * that is the half with no evidence behind it: an operator sets 1800 and nothing in the tree said what
 * happens at the 1800th second, on either call path.
 *
 * These cases run the real Reactor `timeout` against the two call sites in [HarnessAgentWrapper], with the
 * budget shrunk to a second so the gate costs a second rather than the shipped number. What a stream does
 * with that same second is deliberately not covered here: `Flux.timeout` bounds the silence between events,
 * so a stream that keeps answering outlives the budget that caps a batch turn — the difference is spelled
 * out where both operators are applied.
 */
class HarnessAgentTurnTimeoutTest {

    private fun buildWrapper(timeoutSeconds: Long): Pair<HarnessAgentWrapper, HarnessAgent> {
        val agent = mock<HarnessAgent>()
        return HarnessAgentWrapper(
            harnessAgent = agent,
            dangerousTools = emptySet(),
            sessionId = "web-turn-timeout",
            turnTimeoutSeconds = timeoutSeconds,
        ) to agent
    }

    private fun stubBatch(agent: HarnessAgent, reply: Mono<Msg>) {
        whenever(agent.call(any<List<Msg>>(), any<RuntimeContext>())).thenReturn(reply)
    }

    private fun stubStream(agent: HarnessAgent, events: Flux<AgentEvent>) {
        whenever(agent.streamEvents(any<List<Msg>>(), any<RuntimeContext>())).thenReturn(events)
    }

    private fun reply(text: String): Msg = Msg.builder()
        .name("assistant")
        .role(MsgRole.ASSISTANT)
        .content(listOf(TextBlock.builder().text(text).build()))
        .build()

    @Test
    @DisplayName("a batch turn that answers inside its budget is left alone")
    fun batchTurnInsideBudgetIsUntouched() {
        val (wrapper, agent) = buildWrapper(30)
        stubBatch(agent, Mono.just(reply("done")).delayElement(Duration.ofMillis(120)))

        assertEquals("done", wrapper.call("go").content)
    }

    @Test
    @DisplayName("a batch turn that never answers is cut off by its own budget")
    fun batchTurnIsCutOffAtTheBudget() {
        val (wrapper, agent) = buildWrapper(1)
        stubBatch(agent, Mono.never())

        val started = System.currentTimeMillis()
        val thrown = assertThrows(TimeoutException::class.java) { wrapper.call("go") }
        val elapsed = System.currentTimeMillis() - started

        assertTrue(
            thrown.message!!.contains("1000"),
            "the failure must name the budget that fired, got: ${thrown.message}",
        )
        assertTrue(elapsed in 900..5_000, "the cut should come at the 1s budget, took ${elapsed}ms")
    }

    @Test
    @DisplayName("a turn budget of zero leaves a slow batch call alone")
    fun zeroBudgetDisablesTheBatchCap() {
        val (wrapper, agent) = buildWrapper(0)
        stubBatch(agent, Mono.just(reply("late")).delayElement(Duration.ofMillis(1_200)))

        assertEquals("late", wrapper.call("go").content, "a non-positive budget is how an operator turns the limit off")
    }

    @Test
    @DisplayName("a stream that goes silent ends with an error and a terminal event")
    fun silentStreamEndsAsAVisibleFailure() {
        val (wrapper, agent) = buildWrapper(1)
        stubStream(agent, Flux.never())

        val events = wrapper.callStream("go").collectList().block(Duration.ofSeconds(30))!!

        // The consumer contract is an error card followed by the end event. A bare cancellation would leave
        // the caller holding an open turn, and a silent end would read as a finished answer.
        assertEquals(2, events.size, "expected one error event and one end event, got $events")
        val error = assertInstanceOf(ErrorChatEvent::class.java, events[0])
        assertEquals(HarnaxErrorCode.SYSTEM_ERROR.code, error.code)
        assertInstanceOf(EndEventChatEvent::class.java, events[1])
    }
}
