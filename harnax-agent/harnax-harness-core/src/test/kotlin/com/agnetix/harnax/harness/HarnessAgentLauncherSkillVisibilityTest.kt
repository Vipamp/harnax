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
import com.agnetix.harnax.entity.SkillVisibilityPolicy
import com.agnetix.harnax.entity.dto.SkillVisibilityDto
import com.agnetix.harnax.tools.sdk.UserIdentifier
import io.agentscope.core.agent.RuntimeContext
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
 * The delivered policy reaching the list the model is actually shown.
 *
 * Nothing below this line is ours to prove — upstream applies the filter and builds the prompt — but the two
 * ends have to meet: the launcher has to install a filter carrying this run's identity, and it has to install
 * it through the one upstream entry point that runs on every prompt composition. Only an assembled agent can
 * answer whether a restricted skill left the model's list, so that is what these tests ask.
 */
class HarnessAgentLauncherSkillVisibilityTest {

    private fun skill(name: String) = AgentSkill.builder()
        .name(name)
        .description("description of $name")
        .skillContent("# $name")
        .build()

    /** `report` restricted to user 42, `pdf` left alone as it would be with no policy row. */
    private val allowList = SkillVisibilityDto(
        mode = SkillVisibilityPolicy.MODE_ALLOW_LIST,
        userIds = listOf(42L),
    )

    private fun spec(reportPolicy: SkillVisibilityDto?) = AgentSpec.builder()
        .id(0L)
        .name("Writer")
        .description("writes")
        .systemPrompt("write")
        .chatModelId(100L)
        .addSkill(SkillSpec(skillId = 1L, skillName = "report", visibility = reportPolicy))
        .addSkill(SkillSpec(skillId = 2L, skillName = "pdf"))
        .build()

    private fun build(
        workspace: Path,
        reportPolicy: SkillVisibilityDto?,
        userId: Long?,
    ): HarnessAgentWrapper = HarnessAgentLauncher(
        chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
        mcpConfigAdaptor = McpConfigAdaptor { null },
        stateStore = mock(AgentStateStore::class.java),
        skillAdaptor = SkillAdaptor { id -> if (id == 1L) skill("report") else skill("pdf") },
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = mock(ProcessLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = workspace,
    ).createSingleAgent(
        agentSpec = spec(reportPolicy),
        sessionId = "web-7",
        chatSpec = ChatSpec.builder().build(),
        userIdentifier = UserIdentifier(userId),
    )

    /** What the model is shown for its skills, composed the way a turn composes it. */
    private fun skillList(agent: HarnessAgentWrapper): String {
        val middleware = checkNotNull(agent.harnessAgent.delegate)
            .middlewares
            .filterIsInstance<HarnessSkillMiddleware>()
            .single()
        // An empty context on purpose: the identity has to come from the run, not from what upstream chose
        // to put in the call context (design section 4.1).
        return middleware.onSystemPrompt(agent.harnessAgent, RuntimeContext.empty(), "").block().orEmpty()
    }

    @Test
    fun `a policy-restricted skill stays out of the list for a user who is not on it`(@TempDir workspace: Path) {
        val shown = skillList(build(workspace, allowList, 43L))

        assertFalse(shown.contains("report"), "user 43 must not be shown the restricted skill: $shown")
        assertTrue(shown.contains("pdf"), "the unrestricted skill has to survive the same filter run")
    }

    @Test
    fun `the same agent shows it to the user the policy names`(@TempDir workspace: Path) {
        val shown = skillList(build(workspace, allowList, 42L))

        assertTrue(shown.contains("report"), "user 42 is on the list and should see it: $shown")
        assertTrue(shown.contains("pdf"))
    }

    @Test
    fun `an agent with no policies is assembled exactly as before`(@TempDir workspace: Path) {
        // The pass-through invariant: installing a filter only when a policy arrived means an agent that was
        // never configured cannot lose a skill to this tier of the feature.
        val shown = skillList(build(workspace, null, 43L))

        assertNotNull(shown)
        assertTrue(shown.contains("report"), "no policy row means visible: $shown")
        assertTrue(shown.contains("pdf"))
    }

    @Test
    fun `a conversation with no user identity is shown neither restricted skill`(@TempDir workspace: Path) {
        // Channel traffic. Conservative by decision: an allow list cannot be honoured without an identity, and
        // guessing one would hand a scoped skill to the first person who happened to be anonymous.
        val shown = skillList(build(workspace, allowList, null))

        assertFalse(shown.contains("report"), "no identity means no allow-list admission: $shown")
        assertTrue(shown.contains("pdf"))
    }
}
