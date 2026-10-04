package com.agnetix.harnax.harness.compaction

import io.agentscope.core.ReActAgent
import io.agentscope.core.message.ContentBlock
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.model.ChatResponse
import io.agentscope.core.model.GenerateOptions
import io.agentscope.core.model.Model
import io.agentscope.core.model.ToolSchema
import io.agentscope.core.state.AgentState
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import reactor.core.publisher.Flux

/**
 * What `/compact` does to a session's context, and what it must never do.
 *
 * The compactor is the real upstream one and the model is a stub, because the two properties under test come
 * from that combination: which messages survive the cutoff, and what a summary call that failed looks like from
 * the outside. The state is a real [AgentState] for the same reason — the rewrite happens on
 * [AgentState.contextMutable], and a mock would let a wrong reference pass.
 */
class ContextCompactionServiceTest {

    private val summaryText = "The user asked for the workspace files; three were listed."

    /** Answers the one summarization call a compaction makes, in whichever shape the case needs. */
    private class StubModel(
        private val reply: String?,
        private val failure: Throwable? = null,
    ) : Model {
        var calls = 0

        override fun stream(
            messages: List<Msg>,
            tools: List<ToolSchema>?,
            options: GenerateOptions?,
        ): Flux<ChatResponse> {
            calls++
            if (failure != null) return Flux.error(failure)
            if (reply == null) return Flux.empty()
            return Flux.just(
                ChatResponse.builder().content(listOf<ContentBlock>(TextBlock.builder().text(reply).build())).build(),
            )
        }

        override fun getModelName(): String = "stub"
    }

    private class Fixture(
        val state: AgentState,
        val agent: HarnessAgent,
        val delegate: ReActAgent,
        val model: StubModel,
    )

    private fun fixture(
        messages: List<Msg>,
        reply: String? = "The user asked for the workspace files; three were listed.",
        failure: Throwable? = null,
    ): Fixture {
        val state = AgentState.builder().sessionId("s1").context(messages.toMutableList()).build()
        val delegate = mock(ReActAgent::class.java)
        `when`(delegate.getAgentState(any(), any())).thenReturn(state)
        val model = StubModel(reply, failure)
        val agent = mock(HarnessAgent::class.java)
        `when`(agent.delegate).thenReturn(delegate)
        `when`(agent.model).thenReturn(model)
        `when`(agent.name).thenReturn("tester")
        return Fixture(state, agent, delegate, model)
    }

    /** Plain USER/ASSISTANT turns, so the cutoff is arithmetic rather than a tool-pair rescue. */
    private fun conversation(count: Int): List<Msg> = (1..count).map {
        Msg.builder()
            .id("m$it")
            .role(if (it % 2 == 1) MsgRole.USER else MsgRole.ASSISTANT)
            .name(if (it % 2 == 1) "user" else "assistant")
            .textContent("turn $it: " + "x".repeat(200))
            .build()
    }

    private fun compact(
        fixture: Fixture,
        keepTokens: Int? = null,
    ): CompactionOutcome = ContextCompactionService.compact(fixture.agent, "s1", userId = null, keepTokens = keepTokens)

    @Nested
    @DisplayName("a conversation long enough to compact")
    inner class Compacts {
        @Test
        fun `replaces the head with one summary and keeps the tail verbatim`() {
            val fixture = fixture(conversation(25))

            val outcome = compact(fixture)

            val success = assertInstanceOf(CompactionOutcome.Success::class.java, outcome)
            assertTrue(success.compacted)
            assertEquals(25, success.beforeMessages)
            // keepMessages defaults to 20, so 5 of the 25 go into the summary.
            assertEquals(21, success.afterMessages)
            assertTrue(success.afterTokens < success.beforeTokens, "a compaction that grew the context is a bug")

            val context = fixture.state.contextMutable()
            assertEquals(ConversationCompactor.SUMMARY_MSG_NAME, context[0].name)
            assertTrue(context[0].textContent.contains(summaryText))
            assertEquals((6..25).map { "m$it" }, context.drop(1).map { it.id })
        }

        @Test
        fun `saves the live state through the delegate that owns it`() {
            val fixture = fixture(conversation(25))

            compact(fixture)

            verify(fixture.delegate).saveAgentState(null, "s1")
        }

        @Test
        fun `keepTokens from the command moves the cutoff and keeps at least one turn`() {
            val fixture = fixture(conversation(25))

            val outcome = compact(fixture, keepTokens = 1)

            val success = assertInstanceOf(CompactionOutcome.Success::class.java, outcome)
            assertTrue(success.compacted)
            assertEquals(2, success.afterMessages, "one summary plus the single turn the budget leaves room for")
            val context = fixture.state.contextMutable()
            assertEquals(listOf("m25"), context.drop(1).map { it.id })
            assertEquals(1, fixture.model.calls)
        }

        @Test
        fun `a previous summary is folded into the new one instead of stacking`() {
            val first = fixture(conversation(25))
            val afterFirst = compact(first) as CompactionOutcome.Success
            assertEquals(21, afterFirst.afterMessages)

            val second = fixture(first.state.contextMutable().toList(), reply = "second summary")
            val afterSecond = compact(second)

            // 21 is still over keepMessages=20, so the head — the earlier summary — is summarized again.
            val success = assertInstanceOf(CompactionOutcome.Success::class.java, afterSecond)
            assertTrue(success.compacted, "chained compaction should not stall on a session already compacted once")
            assertEquals(
                1,
                second.state.contextMutable().count { it.name == ConversationCompactor.SUMMARY_MSG_NAME },
                "only one summary may head the context",
            )
        }
    }

    @Nested
    @DisplayName("a conversation with nothing to gain")
    inner class NothingToGain {
        @Test
        fun `reports success with the same numbers when the tail would eat the whole conversation`() {
            val fixture = fixture(conversation(5))

            val outcome = compact(fixture)

            val success = assertInstanceOf(CompactionOutcome.Success::class.java, outcome)
            assertFalse(success.compacted)
            assertEquals(success.beforeMessages, success.afterMessages)
            assertEquals(success.beforeTokens, success.afterTokens)
            assertEquals(5, fixture.state.contextMutable().size, "the context must be exactly as it was")
        }

        @Test
        fun `does not ask the model and does not save when there is no context at all`() {
            val fixture = fixture(emptyList())

            val outcome = compact(fixture)

            val success = assertInstanceOf(CompactionOutcome.Success::class.java, outcome)
            assertFalse(success.compacted)
            assertEquals(0, fixture.model.calls, "a command on an empty session must not cost a model call")
            verify(fixture.delegate, never()).saveAgentState(any(), any())
        }

        @Test
        fun `fails without paying for a summary when the agent has no delegate`() {
            val agent = mock(HarnessAgent::class.java)
            val model = StubModel(reply = summaryText)
            `when`(agent.delegate).thenReturn(null)
            `when`(agent.model).thenReturn(model)

            val outcome = ContextCompactionService.compact(agent, "s1", userId = null)

            val failed = assertInstanceOf(CompactionOutcome.Failed::class.java, outcome)
            assertTrue(failed.message.isNotBlank(), "the caller shows this line to the user")
            assertEquals(0, model.calls)
        }
    }

    @Nested
    @DisplayName("a summary call that failed")
    inner class SummaryFailure {
        @Test
        fun `refuses the replacement when the model call throws`() {
            val original = conversation(25)
            val fixture = fixture(original, failure = RuntimeException("upstream 503"))

            val outcome = compact(fixture)

            assertInstanceOf(CompactionOutcome.Failed::class.java, outcome)
            assertEquals(original.map { it.id }, fixture.state.contextMutable().map { it.id })
            verify(fixture.delegate, never()).saveAgentState(any(), any())
        }

        @Test
        fun `refuses the replacement when the model returns nothing`() {
            val original = conversation(25)
            val fixture = fixture(original, reply = null)

            val outcome = compact(fixture)

            assertInstanceOf(CompactionOutcome.Failed::class.java, outcome)
            assertEquals(original.map { it.id }, fixture.state.contextMutable().map { it.id })
            verify(fixture.delegate, never()).saveAgentState(any(), any())
        }

        @Test
        fun `a summary that only mentions the marker is refused too, and refusing is the safe side`() {
            // The marker is matched anywhere in the summary message, not only where upstream writes it, because
            // the two directions are not symmetric: a false positive costs this command one retry and leaves a
            // working context, while a false negative replaces the model's context with an error string the page
            // cannot show and the next turn answers from.
            val original = conversation(25)
            val fixture = fixture(original, reply = "The run reported (Summarization failed) earlier.")

            val outcome = compact(fixture)

            assertInstanceOf(CompactionOutcome.Failed::class.java, outcome)
            assertEquals(original.map { it.id }, fixture.state.contextMutable().map { it.id })
            verify(fixture.delegate, never()).saveAgentState(any(), any())
        }
    }

    @Nested
    @DisplayName("token estimate is the upstream one")
    inner class TokenEstimate {
        @Test
        fun `the before number equals what the upstream counter says about the same context`() {
            val messages = conversation(25)
            val fixture = fixture(messages)

            val outcome = compact(fixture)

            val success = assertInstanceOf(CompactionOutcome.Success::class.java, outcome)
            assertEquals(TokenCounterUtil.calculateToken(messages), success.beforeTokens)
        }
    }
}
