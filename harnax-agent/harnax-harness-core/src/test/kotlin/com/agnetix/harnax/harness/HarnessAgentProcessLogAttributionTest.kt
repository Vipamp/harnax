package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLog
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.agent.provider.middleware.ProcessLogMiddleware
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.harness.team.TeamMemberSpec
import com.agnetix.harnax.harness.team.TeamOrchestrator
import com.agnetix.harnax.harness.team.TeamRuntimeSpec
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.middleware.AgentInput
import io.agentscope.core.state.AgentStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import reactor.core.publisher.Flux
import java.nio.file.Path
import java.util.function.Function

/**
 * Which run a `process_log` line belongs to (AGENT-30).
 *
 * The recorder keeps the run's attribution in a field it is seeded with at assembly, so one instance
 * shared by several agents makes the last build win. A team is exactly that sequence: its members are
 * assembled on the first delegation, long after the lead was built, and the lead keeps calling the model
 * afterwards — those turns wrote their lines against the member's session, agent and tenant.
 *
 * Asserted by running each agent's own middleware chain, because the chain of the built agent is what the
 * turn executes; a check on the builder would not notice an instance handed out twice.
 */
class HarnessAgentProcessLogAttributionTest {

    private val captured = mutableListOf<ProcessLog>()

    private fun launcher(): HarnessAgentLauncher = HarnessAgentLauncher(
        chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
        mcpConfigAdaptor = McpConfigAdaptor { null },
        stateStore = mock(AgentStateStore::class.java),
        skillAdaptor = SkillAdaptor { null },
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = ProcessLogAdaptor { captured.add(it) },
        toolCallLogAdaptor = mock(ToolCallLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")),
    )

    private fun spec(id: Long, name: String, tenantId: Long) = AgentSpec.builder()
        .id(id)
        .name(name)
        .description("does the work")
        .systemPrompt("answer")
        .chatModelId(100L)
        .tenantId(tenantId)
        .build()

    private fun specInfo(id: Long, name: String) = AgentSpecInfoResponse(
        agentId = id,
        agentName = name,
        description = "does the work",
        systemPrompt = "answer",
        modelId = 100L,
    )

    /** The team of the fixture: lead in tenant 5, one member in tenant 6. */
    private fun orchestrator(): TeamOrchestrator {
        val orchestrator = mock(TeamOrchestrator::class.java)
        `when`(orchestrator.spec).thenReturn(
            TeamRuntimeSpec(
                teamId = 7L,
                tenantId = 5L,
                teamName = "Research",
                rootSessionId = "web-team",
                leadAgentSpec = spec(0L, "Research", 5L),
                leadChatSpec = ChatSpec.builder().build(),
                leadSpecInfo = specInfo(0L, "Research"),
                members = emptyList(),
            ),
        )
        return orchestrator
    }

    private fun buildLead(launcher: HarnessAgentLauncher) = launcher.createTeamLead(
        orchestrator = orchestrator(),
        agentSpec = spec(0L, "Research", 5L),
        sessionId = "web-team",
        chatSpec = ChatSpec.builder().build(),
        userIdentifier = UserIdentifier(userId = 1L),
    )

    private fun buildMember(launcher: HarnessAgentLauncher, orchestrator: TeamOrchestrator) = launcher.createTeamMember(
        orchestrator = orchestrator,
        member = TeamMemberSpec(
            memberAgentId = 2L,
            agentName = "Analyst",
            description = "analyses",
            delegationDescription = "when analysis is needed",
            agentSpec = spec(2L, "Analyst", 6L),
            chatSpec = ChatSpec.builder().build(),
            specInfo = specInfo(2L, "Analyst"),
        ),
        childSessionId = "team-web-team-m2",
        rootSessionId = "web-team",
        userIdentifier = UserIdentifier(userId = 1L),
    )

    private fun loggerOf(wrapper: HarnessAgentWrapper) = wrapper.harnessAgent.delegate.middlewares.filterIsInstance<ProcessLogMiddleware>().single()

    /** Runs one agent's own chain and returns the line its start-of-turn log wrote. */
    private fun runOneTurn(wrapper: HarnessAgentWrapper, agentName: String): ProcessLog {
        val agent = mock(Agent::class.java)
        `when`(agent.name).thenReturn(agentName)
        captured.clear()
        loggerOf(wrapper).onAgent(
            agent,
            mock(RuntimeContext::class.java),
            mock(AgentInput::class.java),
            Function<AgentInput, Flux<AgentEvent>> { Flux.empty() },
        ).blockLast()
        return captured.single()
    }

    @Test
    @DisplayName("a lead's turn still logs its own run after a member was assembled")
    fun leadKeepsItsOwnProcessLogAttribution() {
        val launcher = launcher()
        val lead = buildLead(launcher)
        // The order a delegation takes: the member is assembled mid-run, on the delegating thread.
        val member = buildMember(launcher, orchestrator())

        val leadRow = runOneTurn(lead, "Research")
        assertEquals("web-team", leadRow.sessionId, "the lead's turn logged into another run's session")
        assertEquals(5L, leadRow.tenantId, "the lead's turn billed to the member's tenant")
        assertNull(leadRow.agentId, "a lead has no agent row to attribute to (TEAM-04)")
        assertEquals("Research", leadRow.agentName)

        val memberRow = runOneTurn(member, "Analyst")
        assertEquals("team-web-team-m2", memberRow.sessionId)
        assertEquals(6L, memberRow.tenantId)
        assertEquals(2L, memberRow.agentId)
    }

    @Test
    @DisplayName("two sessions get two process loggers, each with its own attribution")
    fun loggerIsNotSharedAcrossSessions() {
        val launcher = launcher()
        val first = launcher.createSingleAgent(
            agentSpec = spec(12L, "Researcher", 9L),
            sessionId = "web-1",
            chatSpec = ChatSpec.builder().build(),
            userIdentifier = UserIdentifier(userId = 1L),
        )
        val second = launcher.createSingleAgent(
            agentSpec = spec(13L, "Writer", 9L),
            sessionId = "web-2",
            chatSpec = ChatSpec.builder().build(),
            userIdentifier = UserIdentifier(userId = 1L),
        )

        assertEquals(false, loggerOf(first) === loggerOf(second), "one logger shared by two runs lets the later build answer for the earlier one")
        // The second build happened after the first agent was already assembled and kept: its own turns
        // must still land on its own session.
        assertEquals("web-1", runOneTurn(first, "Researcher").sessionId)
        assertEquals(12L, runOneTurn(first, "Researcher").agentId)
    }
}
