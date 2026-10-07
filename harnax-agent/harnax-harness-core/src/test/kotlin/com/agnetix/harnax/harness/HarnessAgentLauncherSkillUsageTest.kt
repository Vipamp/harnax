package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.SkillSpec
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.SkillUsageAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.tools.sdk.UserIdentifier
import io.agentscope.core.skill.AgentSkill
import io.agentscope.core.state.AgentStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Path

/**
 * The wiring between a bound skill row and the count its load produces.
 *
 * Two facts make this worth a launcher test rather than only a recorder unit test: the delivered
 * [AgentSkill] carries no database id, so the id a count lands on exists only while the launcher holds both
 * the spec and the skill; and a runtime with no usage adaptor must build exactly the agent it built before,
 * which is the invariant the optional parameter has to keep.
 */
class HarnessAgentLauncherSkillUsageTest {

    private class FakeUsage : SkillUsageAdaptor {
        val batches = mutableListOf<Pair<String, List<Long>>>()
        val users = mutableListOf<Long?>()

        val useBatches = mutableListOf<Pair<String, List<Long>>>()
        val useUsers = mutableListOf<Long?>()

        override fun reportViews(
            sessionId: String,
            skillIds: List<Long>,
            userId: Long?,
        ) {
            batches += sessionId to skillIds
            users += userId
        }

        override fun reportUses(
            sessionId: String,
            skillIds: List<Long>,
            userId: Long?,
        ) {
            useBatches += sessionId to skillIds
            useUsers += userId
        }
    }

    private fun skill(name: String) = AgentSkill.builder()
        .name(name)
        .description("description of $name")
        .skillContent("# $name")
        .build()

    private fun spec(skillId: Long, skillName: String) = AgentSpec.builder()
        .id(0L)
        .name("Writer")
        .description("writes")
        .systemPrompt("write")
        .chatModelId(100L)
        .addSkill(SkillSpec(skillId = skillId, skillName = skillName))
        .build()

    private fun launcher(
        skillAdaptor: SkillAdaptor,
        usage: SkillUsageAdaptor?,
    ) = HarnessAgentLauncher(
        chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
        mcpConfigAdaptor = McpConfigAdaptor { null },
        stateStore = mock(AgentStateStore::class.java),
        skillAdaptor = skillAdaptor,
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = mock(ProcessLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")),
        skillUsageAdaptor = usage,
    )

    private fun delivered(agent: HarnessAgentWrapper) = agent.harnessAgent.skillRepositories
        .filter { it.source == "in-memory" }
        .single()

    @Test
    fun `a delivered skill is counted under the id the spec bound it by`(@TempDir workspace: Path) {
        val usage = FakeUsage()
        val agent = launcher(SkillAdaptor { skill("report-style") }, usage)
            .createSingleAgent(
                agentSpec = spec(skillId = 5L, skillName = "report-style"),
                sessionId = "web-7",
                chatSpec = ChatSpec.builder().build(),
                userIdentifier = UserIdentifier(userId = 1L),
            )

        // The read the harness makes per prompt composition is what produces the event — nothing else calls it.
        assertTrue(usage.batches.isEmpty(), "building an agent must not count a load: ${usage.batches}")

        val skills = delivered(agent).allSkills

        assertEquals(listOf("web-7" to listOf(5L)), usage.batches)
        // Building an agent delivers a skill but does not work through it, so the load is the only event.
        assertTrue(usage.useBatches.isEmpty())
        assertEquals(listOf("report-style"), skills.map { it.name }, "the delivered skill must still be delivered")
    }

    @Test
    fun `a bound skill that did not load is not counted`(@TempDir workspace: Path) {
        // The row was deleted, or the loader refused it: nothing entered this session's context, so a count
        // would credit a skill the model never saw.
        val usage = FakeUsage()
        val agent = launcher(SkillAdaptor { null }, usage)
            .createSingleAgent(
                agentSpec = spec(skillId = 5L, skillName = "report-style"),
                sessionId = "web-7",
                chatSpec = ChatSpec.builder().build(),
                userIdentifier = UserIdentifier(userId = 1L),
            )

        assertTrue(
            agent.harnessAgent.skillRepositories.filter { it.source == "in-memory" }.isEmpty(),
            "nothing was delivered, so no repository should hold a skill",
        )
        assertTrue(usage.batches.isEmpty(), "an undelivered skill must produce no event: ${usage.batches}")
    }

    @Test
    fun `the count names the user this run was built for`(@TempDir workspace: Path) {
        // The identity reaches the runtime on the request, and only the launcher can carry it to the
        // recorder; a recorder built without it would leave every per-user question unanswerable.
        val usage = FakeUsage()
        val agent = launcher(SkillAdaptor { skill("report-style") }, usage)
            .createSingleAgent(
                agentSpec = spec(skillId = 5L, skillName = "report-style"),
                sessionId = "web-7",
                chatSpec = ChatSpec.builder().build(),
                userIdentifier = UserIdentifier(userId = 7L),
            )

        delivered(agent).allSkills

        assertEquals(listOf(7L), usage.users)
    }

    @Test
    fun `a runtime without a usage adaptor still delivers its skills`(@TempDir workspace: Path) {
        val agent = launcher(SkillAdaptor { skill("report-style") }, null)
            .createSingleAgent(
                agentSpec = spec(skillId = 5L, skillName = "report-style"),
                sessionId = "web-7",
                chatSpec = ChatSpec.builder().build(),
                userIdentifier = UserIdentifier(userId = 1L),
            )

        assertEquals(listOf("report-style"), delivered(agent).allSkills.map { it.name })
    }
}
