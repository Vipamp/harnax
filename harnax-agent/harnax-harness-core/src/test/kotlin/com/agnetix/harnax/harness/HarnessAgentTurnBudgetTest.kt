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
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.TeamConfig
import com.agnetix.harnax.harness.team.TeamMemberSpec
import com.agnetix.harnax.harness.team.TeamOrchestrator
import com.agnetix.harnax.harness.team.TeamRole
import com.agnetix.harnax.harness.team.TeamRuntimeSpec
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import io.agentscope.core.state.AgentStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Path

/**
 * Which turn budget a wrapper is built with.
 *
 * That budget is the only thing inside the runtime that ends a turn which never finishes: it caps a
 * whole batch call, and on a stream it bounds the silence between events. A team turn used to be built
 * with `0`, which the wrapper reads as "no budget at all" — so the one run shape the limit existed for,
 * a model call that stops returning, was the shape it was switched off for. The transport's own
 * timeouts are not a substitute: they belong to another service and an operator can raise them.
 */
class HarnessAgentTurnBudgetTest {

    private fun launcher(config: HarnessConfig): HarnessAgentLauncher = HarnessAgentLauncher(
        chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
        mcpConfigAdaptor = McpConfigAdaptor { null },
        stateStore = mock(AgentStateStore::class.java),
        skillAdaptor = SkillAdaptor { null },
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = mock(ProcessLogAdaptor::class.java),
        toolCallLogAdaptor = mock(ToolCallLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")),
        harnessConfig = config,
    )

    private fun spec() = AgentSpec.builder()
        .id(0L)
        .name("Research")
        .description("does the work")
        .systemPrompt("answer")
        .chatModelId(100L)
        .build()

    private fun leadOrchestrator(): TeamOrchestrator {
        val orchestrator = mock(TeamOrchestrator::class.java)
        `when`(orchestrator.spec).thenReturn(
            TeamRuntimeSpec(
                teamId = 7L,
                tenantId = 1L,
                teamName = "Research",
                rootSessionId = "web-team",
                leadAgentSpec = spec(),
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

    private fun leadBudget(config: HarnessConfig): Long = launcher(config).createTeamLead(
        orchestrator = leadOrchestrator(),
        agentSpec = spec(),
        sessionId = "web-team",
        chatSpec = ChatSpec.builder().build(),
        userIdentifier = UserIdentifier(userId = 1L),
    ).turnTimeoutSeconds

    @Test
    @DisplayName("a lone agent turn gets the agent budget it was configured with")
    fun `single agent gets the configured turn budget`() {
        val config = HarnessConfig(turnTimeoutSeconds = 300, team = TeamConfig(turnTimeoutSeconds = 1_800))

        val budget = launcher(config).createSingleAgent(
            agentSpec = AgentSpec.builder()
                .id(12L)
                .name("Researcher")
                .description("does the work")
                .systemPrompt("answer")
                .chatModelId(100L)
                .build(),
            sessionId = "web-12",
            chatSpec = ChatSpec.builder().build(),
            userIdentifier = UserIdentifier(userId = 1L),
        ).turnTimeoutSeconds

        assertEquals(config.turnTimeoutSeconds, budget)
    }

    @Test
    @DisplayName("a team turn is bounded, and by the team's own number")
    fun `team lead turn is bounded by the team budget`() {
        val config = HarnessConfig(turnTimeoutSeconds = 300, team = TeamConfig(turnTimeoutSeconds = 4_200))

        val budget = leadBudget(config)

        assertTrue(budget > 0, "a team turn used to be built with no budget at all (0 = unlimited)")
        assertEquals(config.team.turnTimeoutSeconds, budget, "a team turn must answer to harness.team, not to the lone-agent key")
    }

    @Test
    @DisplayName("the shipped team budget leaves the team layer's limits in front of it")
    fun `default team budget exceeds the budgets it must not preempt`() {
        val team = HarnessConfig().team

        assertTrue(
            team.turnTimeoutSeconds > team.memberTurnTimeoutSeconds,
            "a member that runs one silent tool for ${team.memberTurnTimeoutSeconds}s would kill the run instead of failing its delegation",
        )
        assertTrue(
            team.turnTimeoutSeconds > team.confirmTimeoutSeconds,
            "a confirmation left open for ${team.confirmTimeoutSeconds}s would kill the run",
        )
    }

    @Test
    @DisplayName("a lead and a member are both team turns; only a lone agent is not")
    fun `turn budget follows the role`() {
        val config = HarnessConfig(turnTimeoutSeconds = 300, team = TeamConfig(turnTimeoutSeconds = 1_800))
        val launcher = launcher(config)
        val orchestrator = leadOrchestrator()
        val member = TeamMemberSpec(
            memberAgentId = 12L,
            agentName = "Researcher",
            description = "does the work",
            delegationDescription = "does the work",
            agentSpec = spec(),
            chatSpec = ChatSpec.builder().build(),
            specInfo = AgentSpecInfoResponse(
                agentId = 12L,
                agentName = "Researcher",
                description = "does the work",
                systemPrompt = "answer",
                modelId = 100L,
            ),
        )

        assertEquals(config.turnTimeoutSeconds, launcher.turnBudget(null))
        assertEquals(config.team.turnTimeoutSeconds, launcher.turnBudget(TeamRole.Lead(orchestrator)))
        assertEquals(config.team.turnTimeoutSeconds, launcher.turnBudget(TeamRole.Member(orchestrator, member)))
    }
}
