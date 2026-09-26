package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.agent.provider.middleware.TokenStatsMiddleware
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import io.agentscope.core.state.AgentStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.nio.file.Path

/**
 * That a run's agent carries a token recorder (AGENT-28).
 *
 * The defect was not a wrong number but a missing writer: recording hung off the streaming pipeline only,
 * so every turn taken over the non-streaming endpoint — all of a channel's traffic and every scheduled
 * task — consumed tokens and wrote nothing. Recording now happens where the usage is produced, on the
 * model call, so the question this answers is whether the assembled agent has that hook at all. If it is
 * ever dropped from assembly the symptom returns as silence rather than an error, which is what makes it
 * worth a test.
 *
 * Asserted on the built agent rather than on the builder, because the middleware list of the agent is what
 * both call paths actually run.
 */
class HarnessAgentTokenRecordingTest {

    private fun launcher(): HarnessAgentLauncher = HarnessAgentLauncher(
        chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
        mcpConfigAdaptor = McpConfigAdaptor { null },
        stateStore = mock(AgentStateStore::class.java),
        skillAdaptor = SkillAdaptor { null },
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = mock(ProcessLogAdaptor::class.java),
        toolCallLogAdaptor = mock(ToolCallLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")),
    )

    private fun spec(id: Long) = AgentSpec.builder()
        .id(id)
        .name("Researcher")
        .description("does the work")
        .systemPrompt("answer")
        .chatModelId(100L)
        .build()

    private fun build(sessionId: String): HarnessAgentWrapper = launcher().createSingleAgent(
        agentSpec = spec(12L),
        sessionId = sessionId,
        chatSpec = ChatSpec.builder().build(),
        userIdentifier = UserIdentifier(userId = 1L),
    )

    private fun recorders(wrapper: HarnessAgentWrapper) = wrapper.harnessAgent.delegate.middlewares.filterIsInstance<TokenStatsMiddleware>()

    @Test
    @DisplayName("the assembled agent records token usage on the model call itself")
    fun agentCarriesOneRecorder() {
        assertEquals(1, recorders(build("web-1")).size, "no recorder means a whole call path bills nothing")
    }

    @Test
    @DisplayName("two sessions get two recorders, each with its own attribution")
    fun recorderIsNotSharedAcrossSessions() {
        val first = recorders(build("web-1")).single()
        val second = recorders(build("web-2")).single()

        assertEquals(false, first === second, "one recorder shared by two sessions pays one for the other's tokens")
    }
}
