package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.SkillSpec
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.agent.provider.middleware.TokenStatsMiddleware
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.harness.team.TeamOrchestrator
import com.agnetix.harnax.harness.team.TeamRuntimeSpec
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import io.agentscope.core.state.AgentStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Path

/**
 * What a run gets attributed to in `token_stats` (TEAM-04).
 *
 * A team's lead is configured by the `team` row and no `agent` row stands behind it, which the builder
 * records with the id 0 sentinel (design D1). The sentinel is fine inside the runtime — it is what the
 * `agent_id` columns of the statistics and log tables must receive, since a 0 there reads as an agent
 * whose id nothing resolves and whom every agent-dimension count then adds.
 *
 * Asserted on the seed the assembled agent's recorder carries, because that middleware is what writes the
 * row: the attribution is read where it is consumed, not on a copy an object hands along.
 */
class HarnessAgentRunAttributionTest {

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
        .name(if (id == 0L) "Research" else "Researcher")
        .description("does the work")
        .systemPrompt("answer")
        .chatModelId(100L)
        .addSkill(SkillSpec(skillId = 5L, skillName = "report-style"))
        .build()

    private fun leadOrchestrator(): TeamOrchestrator {
        val orchestrator = mock(TeamOrchestrator::class.java)
        `when`(orchestrator.spec).thenReturn(
            TeamRuntimeSpec(
                teamId = 7L,
                tenantId = 1L,
                teamName = "Research",
                rootSessionId = "web-team",
                leadAgentSpec = spec(0L),
                leadChatSpec = ChatSpec.builder().build(),
                leadSpecInfo = AgentSpecInfoResponse(
                    agentId = 0L,
                    agentName = "Research",
                    description = "does the work",
                    systemPrompt = "answer",
                    modelId = 100L,
                ),
                members = emptyList(),
            ),
        )
        return orchestrator
    }

    /** The seed of the recorder the built agent runs — the object that writes its `token_stats` rows. */
    private fun seed(wrapper: HarnessAgentWrapper) = wrapper.harnessAgent.delegate.middlewares.filterIsInstance<TokenStatsMiddleware>().single().tokenStatBuilder

    private fun leadStat() = seed(
        launcher().createTeamLead(
            orchestrator = leadOrchestrator(),
            agentSpec = spec(0L),
            sessionId = "web-team",
            chatSpec = ChatSpec.builder().build(),
            userIdentifier = UserIdentifier(userId = 1L),
        ),
    ).build()

    @Nested
    @DisplayName("Lead run")
    inner class LeadRunTests {

        @Test
        @DisplayName("a lead's consumption has no agent to attribute it to")
        fun `lead token stat carries no agent id`() {
            val stat = leadStat()
            assertNull(stat.agentId, "the lead sentinel must not reach the agent column as id 0")
            // Only the attribution is absent: dropping the run's other keys would lose its cost.
            assertEquals(100L, stat.modelId)
            assertEquals("web-team", stat.sessionId)
        }
    }

    @Nested
    @DisplayName("Agent run")
    inner class AgentRunTests {

        @Test
        @DisplayName("an agent that exists is still the one its consumption belongs to")
        fun `single agent token stat keeps its agent id`() {
            val stat = seed(
                launcher().createSingleAgent(
                    agentSpec = spec(12L),
                    sessionId = "web-12",
                    chatSpec = ChatSpec.builder().build(),
                    userIdentifier = UserIdentifier(userId = 1L),
                ),
            ).build()

            assertEquals(12L, stat.agentId)
        }
    }
}
