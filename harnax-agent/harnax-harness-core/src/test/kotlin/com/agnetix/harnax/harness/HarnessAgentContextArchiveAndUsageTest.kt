package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.protocol.ContextWindowSource
import com.agnetix.harnax.agent.session.MysqlSessionMessageStore
import com.agnetix.harnax.harness.compaction.CompactionOutcome
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
import io.agentscope.core.state.AgentStateStore
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import reactor.core.publisher.Flux
import java.util.Optional

/**
 * The wrapper's three context abilities: archiving a turn, compacting on command, reporting how full the
 * context is.
 *
 * What makes these worth pinning is that each one writes or reads a different place, and the product
 * requirement is what keeps them apart. The archive is the page's read side and must never be trimmed;
 * compaction rewrites only the model's context; and the usage ratio answers "how close is the automatic
 * compaction", so its numbers have to come from the same ruler the middleware uses.
 */
class HarnessAgentContextArchiveAndUsageTest {

    private lateinit var dataSource: JdbcDataSource
    private lateinit var archive: MysqlSessionMessageStore

    @BeforeEach
    fun setUp() {
        dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:ctxdb_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        archive = MysqlSessionMessageStore(dataSource, "session_message", createIfNotExist = true)
    }

    @AfterEach
    fun tearDown() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DROP TABLE IF EXISTS session_message").use { it.executeUpdate() }
        }
    }

    /** Reports [window] as its context size and answers one summarization call with [reply]. */
    private class StubModel(
        private val window: Int = 0,
        private val reply: String? = SUMMARY_TEXT,
    ) : Model {
        override fun stream(
            messages: List<Msg>,
            tools: List<ToolSchema>?,
            options: GenerateOptions?,
        ): Flux<ChatResponse> = if (reply == null) {
            Flux.empty()
        } else {
            Flux.just(
                ChatResponse.builder().content(listOf<ContentBlock>(TextBlock.builder().text(reply).build())).build(),
            )
        }

        override fun getModelName(): String = "stub"

        override fun getContextWindowSize(): Int = window
    }

    private fun msg(id: String): Msg = Msg.builder()
        .id(id)
        .role(MsgRole.USER)
        .name("user")
        .textContent("x".repeat(50))
        .build()

    private fun conversation(count: Int): List<Msg> = (1..count).map { msg("m$it") }

    private fun state(messages: List<Msg>): AgentState = AgentState.builder()
        .sessionId(SESSION_ID)
        .context(messages.toMutableList())
        .build()

    private fun liveAgent(
        messages: List<Msg>,
        window: Int = 0,
        reply: String? = SUMMARY_TEXT,
    ): Pair<HarnessAgent, ReActAgent> {
        val delegate = mock(ReActAgent::class.java)
        whenever(delegate.getAgentState(any(), any())).thenReturn(state(messages))
        val agent = mock(HarnessAgent::class.java)
        whenever(agent.delegate).thenReturn(delegate)
        whenever(agent.model).thenReturn(StubModel(window, reply))
        whenever(agent.name).thenReturn("tester")
        return agent to delegate
    }

    private fun wrapper(
        agent: HarnessAgent,
        archiveStore: MysqlSessionMessageStore? = archive,
        configured: Int? = null,
        userId: String? = null,
        stateStore: AgentStateStore? = null,
    ): HarnessAgentWrapper {
        if (stateStore != null) whenever(agent.stateStore).thenReturn(stateStore)
        return HarnessAgentWrapper(
            harnessAgent = agent,
            dangerousTools = emptySet(),
            sessionId = SESSION_ID,
            userId = userId,
            sessionMessageStore = archiveStore,
            configuredContextWindow = configured,
        )
    }

    @Nested
    @DisplayName("archiveContext")
    inner class Archiving {

        @Test
        @DisplayName("a turn's whole live context lands in the archive the page reads")
        fun archivesTheWholeContext() {
            val (agent, _) = liveAgent(conversation(3))

            wrapper(agent).archiveContext()

            assertEquals(listOf("m1", "m2", "m3"), archive.load("", SESSION_ID).map { it.id })
        }

        @Test
        @DisplayName("a session the archive has nothing for is recorded before its next turn runs")
        fun seedsAnEmptyArchive() {
            // The automatic path trims inside the turn while the turn-end write happens after it, so the head of
            // a session that predates the archive can be compacted away before it is ever recorded.
            val (agent, _) = liveAgent(conversation(3))

            wrapper(agent).backfillArchive()

            assertEquals(listOf("m1", "m2", "m3"), archive.load("", SESSION_ID).map { it.id })
        }

        @Test
        @DisplayName("a session that already has an archive costs the existence check and nothing else")
        fun leavesAFilledArchiveAlone() {
            archive.archive("", SESSION_ID, conversation(2))
            val (agent, delegate) = liveAgent(conversation(3))

            wrapper(agent).backfillArchive()

            verify(delegate, never()).getAgentState(any(), any())
            assertEquals(listOf("m1", "m2"), archive.load("", SESSION_ID).map { it.id })
        }

        @Test
        @DisplayName("the archive takes the live state, never the persisted copy")
        fun archivesLiveStateNotTheStoreCopy() {
            // The two differ the moment a turn is in flight: the row in `agent_state` is the previous turn's.
            val persisted = mock(AgentStateStore::class.java)
            whenever(persisted.get(any(), any(), any(), eq(AgentState::class.java)))
                .thenReturn(Optional.of(state(conversation(1))))
            val (agent, _) = liveAgent(conversation(3))

            wrapper(agent, stateStore = persisted).archiveContext()

            assertEquals(listOf("m1", "m2", "m3"), archive.load("", SESSION_ID).map { it.id })
        }

        @Test
        @DisplayName("a summary is never archived, so the page shows no summary bubble")
        fun skipsTheSummary() {
            val summary = Msg.builder()
                .id("sum-1")
                .role(MsgRole.USER)
                .name(ConversationCompactor.SUMMARY_MSG_NAME)
                .textContent("Earlier the user asked about the workspace.")
                .build()
            val (agent, _) = liveAgent(listOf(summary, msg("m1")))

            wrapper(agent).archiveContext()

            assertEquals(listOf("m1"), archive.load("", SESSION_ID).map { it.id })
        }

        @Test
        @DisplayName("without an archive store nothing is read from the agent at all")
        fun doesNothingWithoutAStore() {
            val (agent, delegate) = liveAgent(conversation(3))

            wrapper(agent, archiveStore = null).archiveContext()

            verify(agent, never()).delegate
            verify(delegate, never()).getAgentState(any(), any())
        }

        @Test
        @DisplayName("a first failure is retried once and the turn is left standing")
        fun retriesOnceAndSucceeds() {
            val store = mock(MysqlSessionMessageStore::class.java)
            whenever(store.archive(anyOrNull(), anyString(), anyList()))
                .thenThrow(RuntimeException("session database went away"))
                .thenAnswer { 3 }
            val (agent, _) = liveAgent(conversation(3))

            wrapper(agent, archiveStore = store).archiveContext()

            verify(store, times(2)).archive(anyOrNull(), anyString(), anyList())
        }

        @Test
        @DisplayName("a second failure is given up on, not retried further")
        fun stopsAfterTheSecondFailure() {
            val store = mock(MysqlSessionMessageStore::class.java)
            whenever(store.archive(anyOrNull(), anyString(), anyList())).thenThrow(RuntimeException("still down"))
            val (agent, _) = liveAgent(conversation(3))

            wrapper(agent, archiveStore = store).archiveContext()

            verify(store, times(2)).archive(anyOrNull(), anyString(), anyList())
        }
    }

    @Nested
    @DisplayName("contextUsage")
    inner class Usage {

        @Test
        @DisplayName("the estimate is upstream's own ruler: 5 overhead plus ceil of chars over 2.5")
        fun estimatesWithUpstreamsRuler() {
            // One message: 5 structure + ceil("USER"/2.5)=2 + ceil("user"/2.5)=2 + ceil(50 chars/2.5)=20.
            val (agent, _) = liveAgent(listOf(msg("m1")))

            val usage = wrapper(agent, configured = 100_000).contextUsage(null)!!

            assertEquals(29, usage.estimatedTokens)
            assertEquals(1, usage.messageCount)
            assertEquals(29.0 / 100_000.0, usage.ratio, 0.0)
        }

        @Test
        @DisplayName("the configured window divides the ratio and is labelled as configured")
        fun prefersTheConfiguredWindow() {
            val (agent, _) = liveAgent(listOf(msg("m1")))

            val usage = wrapper(agent, configured = 131_072).contextUsage(40_000)!!

            assertEquals(131_072, usage.contextWindow)
            assertEquals(ContextWindowSource.MODEL_FIELD, usage.windowSource)
            assertEquals(40_000, usage.lastCallInputTokens)
        }

        @Test
        @DisplayName("with no configured value the model's own number is reported as inferred")
        fun fallsToTheInferredWindow() {
            val (agent, _) = liveAgent(listOf(msg("m1")), window = 32_768)

            val usage = wrapper(agent).contextUsage(null)!!

            assertEquals(32_768, usage.contextWindow)
            assertEquals(ContextWindowSource.UPSTREAM_TABLE, usage.windowSource)
        }

        @Test
        @DisplayName("when neither knows a window the denominator is upstream's fallback constant")
        fun fallsToTheDefaultWindow() {
            val (agent, _) = liveAgent(listOf(msg("m1")))

            val usage = wrapper(agent).contextUsage(null)!!

            assertEquals(160_000, usage.contextWindow)
            assertEquals(ContextWindowSource.FALLBACK, usage.windowSource)
        }

        @Test
        @DisplayName("the trigger keeps the margin the model's window can afford")
        fun triggerIsWindowMinusReserved() {
            val (agent, _) = liveAgent(listOf(msg("m1")), window = 100_000)

            val usage = wrapper(agent).contextUsage(null)!!

            // reserved is 20_000 by default, so this is where the automatic path would fire.
            assertEquals(80_000, usage.triggerTokens)
            assertEquals(50, usage.triggerMessages)
        }

        @Test
        @DisplayName("a window smaller than the margin still gets a positive trigger")
        fun triggerIsClampedToHalfTheWindow() {
            val (agent, _) = liveAgent(listOf(msg("m1")), window = 15_000)

            val usage = wrapper(agent).contextUsage(null)!!

            assertEquals(7_500, usage.triggerTokens)
        }

        @Test
        @DisplayName("an unknown window triggers on the fallback, not on the guessed denominator")
        fun triggerFallsBackWhenTheModelKnowsNothing() {
            // The automatic path only ever sees the model's own number, so the 160_000 inferred here for the
            // ratio must not be reported as the 140_000 trigger a middleware would never use.
            val (agent, _) = liveAgent(listOf(msg("m1")))

            val usage = wrapper(agent).contextUsage(null)!!

            assertEquals(160_000, usage.triggerTokens)
        }

        @Test
        @DisplayName("after a restart the persisted context is still a usable numerator")
        fun readsTheStoreWhenNoAgentIsLive() {
            val persisted = mock(AgentStateStore::class.java)
            whenever(persisted.get(any(), any(), any(), eq(AgentState::class.java)))
                .thenReturn(Optional.of(state(conversation(2))))
            val agent = mock(HarnessAgent::class.java)
            whenever(agent.stateStore).thenReturn(persisted)
            whenever(agent.model).thenReturn(StubModel())

            assertEquals(2, wrapper(agent).contextUsage(null)?.messageCount)
        }

        @Test
        @DisplayName("a session with neither a live agent nor a stored state has no usage to report")
        fun reportsNothingWhenNothingIsStored() {
            val agent = mock(HarnessAgent::class.java)

            assertNull(wrapper(agent).contextUsage(null))
        }
    }

    @Nested
    @DisplayName("compactManually")
    inner class Compacting {

        @Test
        @DisplayName("refuses outright when the agent has no live state")
        fun refusesWithoutLiveState() {
            val agent = mock(HarnessAgent::class.java)
            whenever(agent.delegate).thenReturn(null)

            val outcome = wrapper(agent).compactManually(null)

            assertInstanceOf(CompactionOutcome.Failed::class.java, outcome)
        }

        @Test
        @DisplayName("saves back through this wrapper's own session and user bucket")
        fun savesThroughItsOwnBucket() {
            val (agent, delegate) = liveAgent(conversation(25))

            val outcome = wrapper(agent, userId = "u9").compactManually(null)

            assertInstanceOf(CompactionOutcome.Success::class.java, outcome)
            verify(delegate).saveAgentState("u9", SESSION_ID)
        }

        @Test
        @DisplayName("a session the archive could not be written to is not compacted")
        fun refusesWhenTheArchiveDidNotLand() {
            // Without this refusal the command would rewrite a context whose head the page has no copy of,
            // and the loss is permanent: the trimmed messages are gone from `agent_state` too.
            val store = mock(MysqlSessionMessageStore::class.java)
            whenever(store.archive(anyOrNull(), anyString(), anyList()))
                .thenThrow(RuntimeException("the session database is down"))
            val (agent, delegate) = liveAgent(conversation(25))

            val outcome = wrapper(agent, archiveStore = store).compactManually(null)

            assertInstanceOf(CompactionOutcome.Failed::class.java, outcome)
            verify(store, times(2)).archive(anyOrNull(), anyString(), anyList())
            verify(delegate, never()).saveAgentState(any(), any())
        }
    }

    private companion object {
        const val SESSION_ID = "web-ctx"
        const val SUMMARY_TEXT = "The user asked for the workspace files; three were listed."
    }
}
