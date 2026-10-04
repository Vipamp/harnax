package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.agent.session.MysqlSessionMessageStore
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.state.AgentState
import io.agentscope.core.state.AgentStateStore
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.file.Path
import java.util.Optional

/**
 * What the chat page reads back as history.
 *
 * Compaction rewrites `AgentState.context` in place, so the model can carry on with a shorter buffer while the
 * page still has to show every original bubble. The three levels of [HarnessAgentLauncher.loadSessionMessages]
 * are what keeps that true, and the summary must never leak through one of them: it is built as a USER message,
 * so a leaked one renders as a bubble the user never typed.
 *
 * The archive side is a real H2 database, because which of the three levels answers and what a summary does to
 * the result are both properties of the read path as a whole, not of one class.
 */
class HarnessAgentSessionHistoryReadTest {

    private lateinit var dataSource: JdbcDataSource
    private lateinit var archive: MysqlSessionMessageStore
    private val stateStore = mock(AgentStateStore::class.java)

    @BeforeEach
    fun setUp() {
        dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:history_${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1")
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

    private fun launcher(archive: MysqlSessionMessageStore?) = HarnessAgentLauncher(
        chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
        mcpConfigAdaptor = McpConfigAdaptor { null },
        stateStore = stateStore,
        skillAdaptor = SkillAdaptor { null },
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = ProcessLogAdaptor { },
        toolCallLogAdaptor = mock(ToolCallLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")),
        sessionMessageStore = archive,
    )

    private fun msg(
        id: String,
        role: MsgRole,
        name: String,
        text: String,
    ): Msg = Msg.builder().id(id).role(role).name(name).textContent(text).build()

    private fun summary(text: String) = msg("sum-1", MsgRole.USER, ConversationCompactor.SUMMARY_MSG_NAME, text)

    private fun stubAgentState(vararg messages: Msg) {
        `when`(stateStore.get(anyString(), anyString(), anyString(), eq(AgentState::class.java)))
            .thenReturn(Optional.of(AgentState.builder().sessionId("s1").context(messages.toList()).build()))
    }

    private fun stubNoAgentState() {
        `when`(stateStore.get(anyString(), anyString(), anyString(), eq(AgentState::class.java)))
            .thenReturn(Optional.empty<AgentState>())
    }

    @Test
    @DisplayName("the archive answers even after the context has been compacted")
    fun `archive wins over a compacted context and carries no summary`() {
        val original = listOf(
            msg("m1", MsgRole.USER, "user", "what files are in the workspace"),
            msg("m2", MsgRole.ASSISTANT, "assistant", "three of them"),
            msg("m3", MsgRole.USER, "user", "summarize the report"),
            msg("m4", MsgRole.ASSISTANT, "assistant", "here is the summary"),
        )
        archive.archive("", "s1", original)
        // What a compacted context looks like: the tail plus a generated summary in front of it.
        stubAgentState(summary("Earlier the user asked about files."), original[3])

        val history = launcher(archive).loadSessionMessages("s1")

        assertEquals(listOf("m1", "m2", "m3", "m4"), history.map { it.id })
        assertEquals(original.map { it.textContent }, history.map { it.textContent })
    }

    @Test
    @DisplayName("an empty archive falls back to the live context and drops the summary")
    fun `fallback to agent_state filters the summary`() {
        stubAgentState(
            summary("Earlier the user asked about files."),
            msg("m1", MsgRole.USER, "user", "hello"),
            msg("m2", MsgRole.ASSISTANT, "assistant", "hi"),
        )

        val history = launcher(archive).loadSessionMessages("s1")

        assertEquals(listOf("m1", "m2"), history.map { it.id })
        assertTrue(history.none { it.name == ConversationCompactor.SUMMARY_MSG_NAME })
    }

    @Test
    @DisplayName("with neither archive nor agent_state the legacy key still reads")
    fun `legacy memory_messages fallback`() {
        stubNoAgentState()
        `when`(stateStore.getList(anyString(), anyString(), eq("memory_messages"), eq(Msg::class.java)))
            .thenReturn(
                listOf(
                    summary("A summary that must not become a bubble."),
                    msg("m1", MsgRole.USER, "user", "old turn"),
                ),
            )

        val history = launcher(archive).loadSessionMessages("s1")

        assertEquals(listOf("m1"), history.map { it.id })
    }

    @Test
    @DisplayName("a deployment with no archive reads from the state store")
    fun `json and in-memory sessions have no archive and lose nothing`() {
        stubAgentState(msg("m1", MsgRole.USER, "user", "hello"))

        val history = launcher(null).loadSessionMessages("s1")

        assertEquals(listOf("m1"), history.map { it.id })
    }

    @Test
    @DisplayName("clearing a session deletes its archive with its state")
    fun `clearSession removes the archive too`() {
        archive.archive("", "s1", listOf(msg("m1", MsgRole.USER, "user", "hello")))
        archive.archive("", "s2", listOf(msg("m2", MsgRole.USER, "user", "keep me")))
        stubAgentState(msg("m1", MsgRole.USER, "user", "hello"))

        launcher(archive).clearSession("s1")

        assertTrue(archive.load("", "s1").isEmpty())
        assertEquals(listOf("m2"), archive.load("", "s2").map { it.id })
        verify(stateStore).delete("", "s1")
        verify(stateStore, never()).delete("", "s2")
    }
}
