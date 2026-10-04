package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.SkillDraftAdaptor
import com.agnetix.harnax.agent.adaptor.SkillDraftIntake
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.harness.skill.SkillDraftSubmitMiddleware
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import io.agentscope.core.middleware.MiddlewareBase
import io.agentscope.core.skill.AgentSkill
import io.agentscope.core.state.AgentStateStore
import io.agentscope.harness.agent.middleware.HarnessSkillMiddleware
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Path

/**
 * Which agent gets to write a skill, decided at assembly.
 *
 * The grant cannot be enforced by a filter, because the framework registers the two authoring tools into its
 * own toolkit and that registration runs past Harnax's tool configuration sweep. So the only question these
 * tests answer is the one an operator cares about: does the model see `skill_manage` on this agent, and is
 * there something wired to hand what it writes to a human afterwards.
 */
class HarnessAgentLauncherSkillSelfWriteTest {

    private val intake = SkillDraftAdaptor { SkillDraftIntake.Queued(7L) }

    private fun spec(selfWrite: Boolean) = AgentSpec.builder()
        .id(0L)
        .name("Writer")
        .description("writes")
        .systemPrompt("write")
        .chatModelId(100L)
        .skillSelfWrite(selfWrite)
        .build()

    private fun build(
        workspace: Path,
        selfWrite: Boolean,
        draftAdaptor: SkillDraftAdaptor?,
    ): HarnessAgentWrapper = HarnessAgentLauncher(
        chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
        mcpConfigAdaptor = McpConfigAdaptor { null },
        stateStore = mock(AgentStateStore::class.java),
        skillAdaptor = SkillAdaptor {
            AgentSkill.builder().name("pdf").description("description of pdf").skillContent("# pdf").build()
        },
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = mock(ProcessLogAdaptor::class.java),
        toolCallLogAdaptor = mock(ToolCallLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = workspace,
        skillDraftAdaptor = draftAdaptor,
    ).createSingleAgent(
        agentSpec = spec(selfWrite),
        sessionId = "web-7",
        chatSpec = ChatSpec.builder().build(),
        userIdentifier = UserIdentifier(42L),
    )

    private fun toolNames(agent: HarnessAgentWrapper): Set<String> = checkNotNull(agent.harnessAgent.delegate).toolkit.toolNames

    private fun middlewares(agent: HarnessAgentWrapper) = checkNotNull(agent.harnessAgent.delegate).middlewares

    @Test
    fun `an agent granted skill self-write is given the authoring tools`(@TempDir workspace: Path) {
        val names = toolNames(build(workspace, selfWrite = true, draftAdaptor = intake))

        assertTrue(names.contains("skill_manage"), "granted agent must have the tool: $names")
        assertTrue(names.contains("propose_skill"), "the one-line proposal entry point goes with it: $names")
    }

    @Test
    fun `an agent without the grant cannot author a skill at all`(@TempDir workspace: Path) {
        // The direction that matters: the tools are refused at assembly rather than offered and filtered,
        // because nothing downstream of the framework's own registration could take them back.
        val names = toolNames(build(workspace, selfWrite = false, draftAdaptor = intake))

        assertFalse(names.contains("skill_manage"), "unganted agent must not have the tool: $names")
        assertFalse(names.contains("propose_skill"), "unganted agent must not have the tool: $names")
    }

    @Test
    fun `a grant with no review queue behind it installs nothing`(@TempDir workspace: Path) {
        // A draft nobody can read is unreviewed text in a workspace, which is the outcome the tier exists to
        // avoid; the grant is then inert, and the agent is assembled exactly as one that never had it.
        val agent = build(workspace, selfWrite = true, draftAdaptor = null)
        val names = toolNames(agent)

        assertFalse(names.contains("skill_manage"), "no intake means no authoring tools: $names")
        assertFalse(middlewares(agent).anySelfWrite(), "and nothing to offer a draft with: $names")
    }

    @Test
    fun `the granted agent is wired to hand its drafts to a human`(@TempDir workspace: Path) {
        val agent = build(workspace, selfWrite = true, draftAdaptor = intake)

        assertTrue(middlewares(agent).anySelfWrite(), "the offer after the turn has to be installed")
        assertNotNull(
            middlewares(agent).filterIsInstance<HarnessSkillMiddleware>().singleOrNull(),
            "granting self-write must not drop the skill delivery path",
        )
    }

    private fun List<MiddlewareBase>.anySelfWrite(): Boolean = any { it is SkillDraftSubmitMiddleware }
}
