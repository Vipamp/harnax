package com.agnetix.harnax.agent.provider.middleware

import com.agnetix.harnax.agent.adaptor.SkillUsageAdaptor
import com.agnetix.harnax.entity.ToolInvocationLog
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationAdaptor
import com.agnetix.harnax.tools.sdk.adaptor.ToolInvocationEvent
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.AgentEventType
import io.agentscope.core.event.ToolResultEndEvent
import io.agentscope.core.event.ToolResultTextDeltaEvent
import io.agentscope.core.message.ToolResultState
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.core.middleware.ActingInput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import java.util.function.Function

/**
 * One row per call, only on a terminal state, and never at the cost of the turn (design I1/I2, D9).
 *
 * The assertions are about those three promises: a call that ends is filed once, a call that never ends is
 * filed as interrupted rather than lost, and a stream that carries on is unaffected by what the recorder did.
 */
class ToolInvocationMiddlewareTest {

    private val events = mutableListOf<ToolInvocationEvent>()
    private val uses = mutableListOf<Pair<String, List<Long>>>()
    private val agent = mock(Agent::class.java)
    private val ctx = mock(RuntimeContext::class.java)

    private inner class FakeSkillUsage : SkillUsageAdaptor {
        override fun reportViews(
            sessionId: String,
            skillIds: List<Long>,
            userId: Long?,
        ) {
            // Loads are reported by SkillViewRecorder, not by this middleware; nothing to record here.
        }

        override fun reportUses(
            sessionId: String,
            skillIds: List<Long>,
            userId: Long?,
        ) {
            uses += sessionId to skillIds
        }
    }

    private fun middleware(
        mcpIdsByTool: Map<String, Long> = emptyMap(),
        cliIdsByCommand: Map<String, Long> = emptyMap(),
        builtinToolNames: Set<String> = emptySet(),
        adminSkillIdsBySkillId: Map<String, Long> = emptyMap(),
    ) = ToolInvocationMiddleware(
        adaptor = ToolInvocationAdaptor { events.add(it) },
        tenantId = 5L,
        agentId = 7L,
        sessionId = "web-1",
        userId = 9L,
        mcpIdsByTool = mcpIdsByTool,
        cliIdsByCommand = cliIdsByCommand,
        builtinToolNames = builtinToolNames,
        skillUsageAdaptor = FakeSkillUsage(),
        adminSkillIdsBySkillId = adminSkillIdsBySkillId,
    )

    private fun actingInput(vararg calls: ToolUseBlock): ActingInput {
        val input = mock(ActingInput::class.java)
        `when`(input.toolCalls).thenReturn(calls.toList())
        return input
    }

    private fun end(id: String?, name: String, state: ToolResultState): ToolResultEndEvent {
        val event = mock(ToolResultEndEvent::class.java)
        `when`(event.type).thenReturn(AgentEventType.TOOL_RESULT_END)
        // An id-less end event is left unstubbed rather than stubbed with null: Mockito already answers
        // null for that getter, and that is what such an event carries.
        if (id != null) `when`(event.toolCallId).thenReturn(id)
        `when`(event.toolCallName).thenReturn(name)
        `when`(event.state).thenReturn(state)
        return event
    }

    private fun delta(id: String, name: String, text: String): ToolResultTextDeltaEvent {
        val event = mock(ToolResultTextDeltaEvent::class.java)
        `when`(event.type).thenReturn(AgentEventType.TOOL_RESULT_TEXT_DELTA)
        `when`(event.toolCallId).thenReturn(id)
        `when`(event.toolCallName).thenReturn(name)
        `when`(event.delta).thenReturn(text)
        return event
    }

    @Nested
    @DisplayName("terminal states")
    inner class TerminalStates {
        @Test
        fun `a successful call files exactly one row with its outcome and duration`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware(builtinToolNames = setOf("now"))

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.just(end("t1", "now", ToolResultState.SUCCESS)) }))
                .expectNextCount(1)
                .verifyComplete()

            assertEquals(1, events.size)
            val event = events.single()
            assertEquals(ToolInvocationLog.OUTCOME_SUCCESS, event.outcome)
            assertEquals(ToolInvocationLog.KIND_BUILTIN, event.kind)
            assertEquals("now", event.toolName)
            assertEquals(5L, event.tenantId)
            assertEquals(7L, event.agentId)
            assertEquals("web-1", event.sessionId)
            assertEquals(9L, event.userId)
            assertTrue(event.endEpochMilli >= event.startEpochMilli)
        }

        @Test
        fun `every terminal state maps to its own outcome`() {
            val cases = listOf(
                ToolResultState.ERROR to ToolInvocationLog.OUTCOME_ERROR,
                ToolResultState.DENIED to ToolInvocationLog.OUTCOME_DENIED,
                ToolResultState.INTERRUPTED to ToolInvocationLog.OUTCOME_INTERRUPTED,
            )
            cases.forEach { (state, outcome) ->
                events.clear()
                val call = ToolUseBlock("t1", "now", emptyMap())
                val mw = middleware()
                StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.just(end("t1", "now", state)) }))
                    .expectNextCount(1)
                    .verifyComplete()
                assertEquals(outcome, events.single().outcome)
            }
        }

        @Test
        fun `a running end event files nothing because the call is still in flight`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.just(end("t1", "now", ToolResultState.RUNNING)) }))
                .expectNextCount(1)
                .verifyComplete()

            // I2: RUNNING never reaches the table. Nothing is filed here, and the start is kept rather than
            // dropped, so a later terminal event for the same id still gets its row.
            assertTrue(events.none { it.outcome == "RUNNING" })
        }
    }

    @Nested
    @DisplayName("payload")
    inner class Payload {
        @Test
        fun `result text accumulates across deltas and reaches the row`() {
            val call = ToolUseBlock("t1", "read_file", emptyMap())
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(call),
                    Function {
                        Flux.just(delta("t1", "read_file", "alpha "), delta("t1", "read_file", "beta"), end("t1", "read_file", ToolResultState.SUCCESS))
                    },
                ),
            ).expectNextCount(3).verifyComplete()

            assertEquals("alpha beta", events.single().resultText)
        }

        @Test
        fun `deltas of another call never mix in`() {
            val one = ToolUseBlock("t1", "a", emptyMap())
            val two = ToolUseBlock("t2", "b", emptyMap())
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(one, two),
                    Function {
                        Flux.just(delta("t1", "a", "for-one"), delta("t2", "b", "for-two"), end("t1", "a", ToolResultState.SUCCESS), end("t2", "b", ToolResultState.SUCCESS))
                    },
                ),
            ).expectNextCount(4).verifyComplete()

            assertEquals(2, events.size)
            assertEquals("for-one", events[0].resultText)
            assertEquals("for-two", events[1].resultText)
        }

        @Test
        fun `tool input is filed as json so a shell command can be read back`() {
            val call = ToolUseBlock("t1", "execute", mapOf("command" to "gh pr view 12"))
            val mw = middleware()

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.just(end("t1", "execute", ToolResultState.SUCCESS)) }))
                .expectNextCount(1)
                .verifyComplete()

            assertTrue(events.single().argsJson!!.contains("gh pr view 12"))
        }

        @Test
        fun `a failure states its reason instead of leaving the row silent`() {
            val call = ToolUseBlock("t1", "send_email", emptyMap())
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(call),
                    Function {
                        Flux.just(delta("t1", "send_email", "smtp refused"), end("t1", "send_email", ToolResultState.ERROR))
                    },
                ),
            ).expectNextCount(2).verifyComplete()

            assertTrue(events.single().errorMessage!!.contains("smtp refused"))
        }
    }

    @Nested
    @DisplayName("unresolved and mismatched")
    inner class Unresolved {
        @Test
        fun `a stream that completes without an end event files the call as interrupted`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.empty<AgentEvent>() })).verifyComplete()

            assertEquals(ToolInvocationLog.OUTCOME_INTERRUPTED, events.single().outcome)
        }

        @Test
        fun `a stream that fails carries the failure text into the interrupted row`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.error(RuntimeException("sandbox died")) }))
                .expectError(RuntimeException::class.java)
                .verify()

            val event = events.single()
            assertEquals(ToolInvocationLog.OUTCOME_INTERRUPTED, event.outcome)
            assertTrue(event.errorMessage!!.contains("sandbox died"))
        }

        @Test
        fun `a resolved call is not filed twice`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()

            // A repeated terminal frame for one id is the shape this pins: the accumulator is dropped as the
            // first END is handled, so the second has nothing left to time and files nothing.
            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(call),
                    Function { Flux.just(end("t1", "now", ToolResultState.SUCCESS), end("t1", "now", ToolResultState.SUCCESS)) },
                ),
            ).expectNextCount(2).verifyComplete()

            assertEquals(1, events.size)
        }

        @Test
        fun `the name recorded at the start wins when the end event disagrees`() {
            val call = ToolUseBlock("t1", "github_search", emptyMap())
            val mw = middleware(mcpIdsByTool = mapOf("github_search" to 11L))

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.just(end("t1", "renamed_by_upstream", ToolResultState.SUCCESS)) }))
                .expectNextCount(1)
                .verifyComplete()

            assertEquals("github_search", events.single().toolName)
            assertEquals(11L, events.single().mcpId)
        }

        @Test
        fun `an end event for an unknown id files nothing`() {
            val mw = middleware()

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(), Function { Flux.just(end("t9", "now", ToolResultState.SUCCESS)) }))
                .expectNextCount(1)
                .verifyComplete()

            // Nothing was timed for this id, so a row would carry a duration invented here rather than measured.
            assertTrue(events.isEmpty())
        }

        @Test
        fun `two nameless calls of one name file two rows`() {
            // No id gives nothing to tell the two apart, but the count is still owed to both: the second
            // start must not erase the first accumulator, and the two ENDs are answered in issue order.
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(ToolUseBlock(null, "now", emptyMap()), ToolUseBlock(null, "now", emptyMap())),
                    Function { Flux.just(end(null, "now", ToolResultState.SUCCESS), end(null, "now", ToolResultState.SUCCESS)) },
                ),
            ).expectNextCount(2).verifyComplete()

            assertEquals(2, events.size)
        }

        @Test
        fun `a nameless start matched by an id-bearing end still files one row`() {
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(agent, ctx, actingInput(ToolUseBlock(null, "now", emptyMap())), Function { Flux.just(end("t1", "now", ToolResultState.SUCCESS)) }),
            ).expectNextCount(1).verifyComplete()

            // The keys never match, so only a fallback by name files this call at all.
            assertEquals(1, events.size)
        }

        @Test
        fun `an interrupted call keeps the output it had already streamed`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(call),
                    Function {
                        Flux.just<AgentEvent>(delta("t1", "now", "half an answer")).concatWith(Flux.error(RuntimeException("sandbox died")))
                    },
                ),
            ).expectNextCount(1).verifyError()

            val event = events.single()
            assertEquals(ToolInvocationLog.OUTCOME_INTERRUPTED, event.outcome)
            assertEquals("half an answer", event.resultText)
            assertTrue(event.errorMessage!!.contains("sandbox died"))
        }
    }

    @Nested
    @DisplayName("skill use")
    inner class SkillUse {
        private fun loadCall(path: String) = ToolUseBlock(
            "t1",
            ToolInvocationClassifier.SKILL_LOAD_TOOL_NAME,
            mapOf("skillId" to "web-search_custom", "path" to path),
        )

        @Test
        fun `reading the skill body successfully reports one use for the delivered skill`() {
            val mw = middleware(adminSkillIdsBySkillId = mapOf("web-search_custom" to 44L))

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(loadCall("SKILL.md")),
                    Function {
                        Flux.just(end("t1", ToolInvocationClassifier.SKILL_LOAD_TOOL_NAME, ToolResultState.SUCCESS))
                    },
                ),
            ).expectNextCount(1).verifyComplete()

            assertEquals(listOf("web-1" to listOf(44L)), uses)
        }

        @Test
        fun `reading a resource file is not a use`() {
            val mw = middleware(adminSkillIdsBySkillId = mapOf("web-search_custom" to 44L))

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(loadCall("references/api.md")),
                    Function {
                        Flux.just(end("t1", ToolInvocationClassifier.SKILL_LOAD_TOOL_NAME, ToolResultState.SUCCESS))
                    },
                ),
            ).expectNextCount(1).verifyComplete()

            assertTrue(uses.isEmpty())
        }

        @Test
        fun `a failed load is not a use`() {
            val mw = middleware(adminSkillIdsBySkillId = mapOf("web-search_custom" to 44L))

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(loadCall("SKILL.md")),
                    Function {
                        Flux.just(end("t1", ToolInvocationClassifier.SKILL_LOAD_TOOL_NAME, ToolResultState.ERROR))
                    },
                ),
            ).expectNextCount(1).verifyComplete()

            assertTrue(uses.isEmpty())
        }

        @Test
        fun `a skill id this run was not delivered asks for nothing`() {
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(loadCall("SKILL.md")),
                    Function {
                        Flux.just(end("t1", ToolInvocationClassifier.SKILL_LOAD_TOOL_NAME, ToolResultState.SUCCESS))
                    },
                ),
            ).expectNextCount(1).verifyComplete()

            assertTrue(uses.isEmpty())
        }
    }

    @Nested
    @DisplayName("pass through and safety")
    inner class PassThrough {
        @Test
        fun `every event of the acting stream reaches the caller untouched`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()
            val e1 = delta("t1", "now", "text")
            val e2 = end("t1", "now", ToolResultState.SUCCESS)

            StepVerifier.create(mw.onActing(agent, ctx, actingInput(call), Function { Flux.just(e1, e2) }))
                .expectNext(e1, e2)
                .verifyComplete()
        }

        @Test
        fun `an adaptor that throws does not fail the turn`() {
            val throwing = ToolInvocationMiddleware(
                adaptor = ToolInvocationAdaptor { throw IllegalStateException("reporter broke") },
                tenantId = null,
                agentId = null,
                sessionId = "web-1",
                userId = null,
                mcpIdsByTool = emptyMap(),
                cliIdsByCommand = emptyMap(),
                builtinToolNames = emptySet(),
                skillUsageAdaptor = null,
                adminSkillIdsBySkillId = emptyMap(),
            )
            val call = ToolUseBlock("t1", "now", emptyMap())

            StepVerifier.create(throwing.onActing(agent, ctx, actingInput(call), Function { Flux.just(end("t1", "now", ToolResultState.SUCCESS)) }))
                .expectNextCount(1)
                .verifyComplete()
        }
    }
}
