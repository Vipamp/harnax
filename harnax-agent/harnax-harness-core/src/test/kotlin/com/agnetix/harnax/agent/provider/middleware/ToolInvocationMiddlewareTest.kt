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

    private fun delta(
        id: String?,
        name: String,
        text: String?,
    ): ToolResultTextDeltaEvent {
        val event = mock(ToolResultTextDeltaEvent::class.java)
        `when`(event.type).thenReturn(AgentEventType.TOOL_RESULT_TEXT_DELTA)
        // Same shape as `end`: an id-less delta is left unstubbed, since Mockito already answers null and
        // that is what such an event carries. An empty or null text is left unstubbed for the same reason:
        // stubbing "" here would build a frame no upstream sends, as the runtime answers null for the text a
        // keep-alive frame carries.
        if (id != null) `when`(event.toolCallId).thenReturn(id)
        `when`(event.toolCallName).thenReturn(name)
        if (!text.isNullOrEmpty()) `when`(event.delta).thenReturn(text)
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
            // A success has no reason to state: `failureText` special-cases this outcome, and nothing else
            // in the suite reads that branch.
            assertNull(event.errorMessage)
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

            // I2: RUNNING never reaches the table, so the batch has to be read while the stream is still open.
            // The order that lets this read the in-flight state is a property of the test's source, not of the
            // middleware: `Flux.just(...).concatWith(Flux.never())` hands `onNext` over synchronously on
            // subscribe, and StepVerifier runs a `then` step inside that call stack, ahead of the cancel. Add a
            // `publishOn` to this stream, or give `next` an async fake, and whether `then` lands before the
            // stream-end sweep becomes an implementation detail of whoever wrote the source. What this case does
            // not prove is that a call left unterminal is owed the stream-end interrupted row —
            // `a stream that completes without an end event files the call as interrupted` and
            // `a cancelled stream files the open call as interrupted` prove that, by asserting the row.
            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(call),
                    Function { Flux.just<AgentEvent>(end("t1", "now", ToolResultState.RUNNING)).concatWith(Flux.never()) },
                ),
            ).expectNextCount(1).then { assertTrue(events.isEmpty()) }.thenCancel().verify()
        }

        @Test
        fun `a running end event leaves the start open for its terminal frame`() {
            val call = ToolUseBlock("t1", "now", emptyMap())
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(call),
                    Function {
                        Flux.just(end("t1", "now", ToolResultState.RUNNING), end("t1", "now", ToolResultState.SUCCESS))
                    },
                ),
            ).expectNextCount(2).verifyComplete()

            // One row, and its outcome is the second frame's: the RUNNING frame filed nothing and consumed
            // nothing, so the start it left behind is still there to be timed. The count holds the dedupe and
            // the one-row rule at the same time.
            assertEquals(1, events.size)
            assertEquals(ToolInvocationLog.OUTCOME_SUCCESS, events.single().outcome)
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
        fun `a lone empty delta leaves the result text null`() {
            // Empty text is the ordinary shape of a keep-alive or heartbeat frame, and it says nothing about the
            // body. Leaving a literal "null" in the accumulator would file "this call produced no excerpt" as
            // "this call produced an excerpt whose content is the word null", so the guard on the delta is what
            // this pins — the falsifier is the unguarded append, which writes exactly that string.
            val call = ToolUseBlock("t1", "read_file", emptyMap())
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(call),
                    Function { Flux.just<AgentEvent>(delta("t1", "read_file", ""), end("t1", "read_file", ToolResultState.SUCCESS)) },
                ),
            ).expectNextCount(2).verifyComplete()

            assertNull(events.single().resultText)
        }

        @Test
        fun `a delta carrying an empty string leaves the result text null`() {
            // Not the helper: it deliberately folds an empty text into "no stub at all", so Mockito answers
            // null and that case pins only the null half of the guard. The isNullOrEmpty half needs a frame
            // whose delta really is the empty string — the shape a keep-alive carries when it reports an empty
            // chunk rather than nothing. Appending it would open a buffer with nothing in it, and `emit` hands
            // that straight through: "this call produced no body" filed as "this call produced a body that is
            // empty", which the read side's blank check cannot tell apart from a body worth showing.
            val call = ToolUseBlock("t1", "read_file", emptyMap())
            val mw = middleware()
            val emptyDelta = mock(ToolResultTextDeltaEvent::class.java)
            `when`(emptyDelta.type).thenReturn(AgentEventType.TOOL_RESULT_TEXT_DELTA)
            `when`(emptyDelta.toolCallId).thenReturn("t1")
            `when`(emptyDelta.toolCallName).thenReturn("read_file")
            `when`(emptyDelta.delta).thenReturn("")

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(call),
                    Function { Flux.just<AgentEvent>(emptyDelta, end("t1", "read_file", ToolResultState.SUCCESS)) },
                ),
            ).expectNextCount(2).verifyComplete()

            assertNull(events.single().resultText)
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
        fun `a renamed end event does not strand the next call of the same name`() {
            // The frame above renames itself; here that rename is the hazard rather than the curiosity. Pairing
            // may only look at the name queue: taking a key out of it under the frame's own name leaves the
            // queue the start actually sits in holding an already-spent key, and the next nameless frame of
            // that name pairs with the ghost, gives up when the table has it no more and loses the row.
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(ToolUseBlock("t1", "github_search", emptyMap()), ToolUseBlock("t2", "github_search", emptyMap())),
                    Function {
                        Flux.just(end("t1", "renamed_by_upstream", ToolResultState.SUCCESS), end(null, "github_search", ToolResultState.SUCCESS))
                    },
                ),
            ).expectNextCount(2).verifyComplete()

            assertEquals(2, events.size)
            assertEquals(listOf("github_search", "github_search"), events.map { it.toolName })
            assertEquals(ToolInvocationLog.OUTCOME_SUCCESS, events[0].outcome)
            assertEquals(ToolInvocationLog.OUTCOME_SUCCESS, events[1].outcome)
        }

        @Test
        fun `a renamed nameless end event is left to the stream-end fallback`() {
            // This is the concession design section 3 records, so it is a requirement and not a defect: a
            // terminal frame that carries neither an id nor a name any queue holds cannot be paired, and the
            // call behind it is owed the stream-end INTERRUPTED row. Pairing it with the only start still open
            // would be a guess, and a wrong guess files a genuinely interrupted call as a success — design I4
            // then moves that row from the aggregate table's `interruptions` into its `successes`, miscounting
            // both. The rename warning `resolve` logs sits after pairing, so this path never emits it.
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(ToolUseBlock(null, "github_search", emptyMap())),
                    Function { Flux.just(end(null, "gh_search", ToolResultState.SUCCESS)) },
                ),
            ).expectNextCount(1).verifyComplete()

            assertEquals(1, events.size)
            val event = events.single()
            assertEquals(ToolInvocationLog.OUTCOME_INTERRUPTED, event.outcome)
            assertEquals("stream ended before the tool returned", event.errorMessage)
            assertEquals("github_search", event.toolName)
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
            // The second reader of the key removal `resolve` does on the name queue: a row count alone survives
            // that line's deletion, because a stranded start still files a row at the stream end — the wrong one.
            assertEquals(listOf(ToolInvocationLog.OUTCOME_SUCCESS, ToolInvocationLog.OUTCOME_SUCCESS), events.map { it.outcome })
        }

        @Test
        fun `a nameless start matched by an id-bearing end files one row with its text`() {
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(ToolUseBlock(null, "now", emptyMap())),
                    // The explicit type argument stays: in a SAM position the left end of a chain inherits no
                    // expected element type, so the mixed frames would be inferred as their common supertype.
                    Function { Flux.just<AgentEvent>(delta("t1", "now", "partial"), end("t1", "now", ToolResultState.SUCCESS)) },
                ),
            ).expectNextCount(2).verifyComplete()

            // The keys never match, so only a fallback by name files this call at all — and the text streamed
            // under the frame's id, not under the start's name, so reading the body is the second half of the
            // pairing and the row is not owed only a count.
            assertEquals(1, events.size)
            assertEquals("partial", events.single().resultText)
        }

        @Test
        fun `an unresolved start with an id files the text streamed under its name`() {
            val mw = middleware()

            // The mirror image: an identifiable start, frames that carry only a name, and no terminal frame, so
            // the row is filed by the stream-end sweep and looks its text up by the start key then the name.
            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(ToolUseBlock("t1", "now", emptyMap())),
                    Function { Flux.just<AgentEvent>(delta(null, "now", "half a sentence")) },
                ),
            ).expectNextCount(1).verifyComplete()

            val event = events.single()
            assertEquals(ToolInvocationLog.OUTCOME_INTERRUPTED, event.outcome)
            assertEquals("half a sentence", event.resultText)
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

        @Test
        fun `a cancelled stream files the open call as interrupted`() {
            val mw = middleware()

            // The third way a stream ends: nobody failed it and it did not complete, the subscriber just walked
            // away. Design section 3 lists cancel beside error, so the sweep has to run here too.
            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(ToolUseBlock("t1", "now", emptyMap())),
                    Function { Flux.just<AgentEvent>(delta("t1", "now", "half an answer")).concatWith(Flux.never<AgentEvent>()) },
                ),
            ).expectNextCount(1).thenCancel().verify()

            val event = events.single()
            assertEquals(ToolInvocationLog.OUTCOME_INTERRUPTED, event.outcome)
            assertEquals("half an answer", event.resultText)
            // No throwable came through, so the reason is the bare fact that the stream stopped first.
            assertEquals("stream ended before the tool returned", event.errorMessage)
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

        @Test
        fun `reading a skill body through another tool reports no use`() {
            val mw = middleware(adminSkillIdsBySkillId = mapOf("s1" to 44L))

            // Every argument says skill body, but this is a plain file read. The loader's own name is what
            // makes a use, so reading a skill's instructions through some other tool must not report one.
            StepVerifier.create(
                mw.onActing(
                    agent,
                    ctx,
                    actingInput(ToolUseBlock("t1", "read_file", mapOf("skillId" to "s1", "path" to "SKILL.md"))),
                    Function { Flux.just(end("t1", "read_file", ToolResultState.SUCCESS)) },
                ),
            ).expectNextCount(1).verifyComplete()

            assertTrue(uses.isEmpty())
            assertEquals(1, events.size)
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

        @Test
        fun `a call without a name files nothing and does not break the turn`() {
            // `ToolUseBlock.name` comes out of the model's JSON and upstream validates nothing, so it can be
            // null. A nameless call has no key to register under, no kind to decide and no writable
            // `tool_name`, so its row could not have been written anyway: the cost is one row, never the turn.
            val mw = middleware()

            // The stream running to completion is the point; an empty batch alone would also be true had
            // onActing thrown before it ever subscribed.
            StepVerifier.create(
                mw.onActing(agent, ctx, actingInput(ToolUseBlock("t1", null, emptyMap())), Function { Flux.just(end("t1", "now", ToolResultState.SUCCESS)) }),
            ).expectNextCount(1).verifyComplete()

            assertTrue(events.isEmpty())
        }

        @Test
        fun `a call with a blank name files nothing and does not break the turn`() {
            // The same reasoning as the null case, and the same column: `name` is model JSON and nothing
            // validates it, so an empty string arrives too. The registry refuses a blank tool name
            // (`ToolRegistry.registerTool` throws), so no row this feature writes can be true.
            val mw = middleware()

            StepVerifier.create(
                mw.onActing(agent, ctx, actingInput(ToolUseBlock("t1", "", emptyMap())), Function { Flux.just(end("t1", "now", ToolResultState.SUCCESS)) }),
            ).expectNextCount(1).verifyComplete()

            assertTrue(events.isEmpty())
        }
    }
}
