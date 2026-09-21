package com.agnetix.harnax.harness

import io.agentscope.core.model.ChatModelBase
import io.agentscope.core.skill.AgentSkill
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Path

/**
 * Who wins when two skills carry the same name, and which repositories the built agent ends up
 * consulting. Both questions decide behaviour at model time, not build time, so they are asserted
 * on the composed agent rather than on the builder's own fields.
 */
class HarnessAgentBuilderSkillTest {

    private fun skill(name: String, content: String) = AgentSkill.builder()
        .name(name)
        .description("description of $name")
        .skillContent(content)
        .build()

    private fun buildAgent(workspace: Path, vararg skills: AgentSkill) = HarnessAgentBuilder()
        .name("tester")
        .description("tester")
        .maxIters(1)
        .systemPrompt("prompt")
        .model(mock(ChatModelBase::class.java))
        .workspace(workspace)
        .apply { skills.forEach { addSkill(it) } }
        .build()

    @Test
    fun `only the repositories the operator delivered are consulted`(@TempDir workspace: Path) {
        // The harness composes a workspace-backed repository on top of the user-supplied ones and
        // merges low-to-high by skill name, so a SKILL.md the agent writes into its own sandbox
        // would outrank the one admin configured for it. harnax has no self-learning skill loop
        // (`enableSkillManageTool` is never called), so the only source of skills is the spec.
        val agent = buildAgent(workspace, skill("pdf-tools", "# from admin"))

        assertNotNull(agent.skillRepositories)
        assertTrue(
            agent.skillRepositories.none { it is WorkspaceSkillRepository },
            "workspace skills directory must not be able to outrank a delivered skill: " +
                agent.skillRepositories.map { it.repositoryInfo },
        )
    }

    @Test
    fun `two delivered skills with the same name resolve to the one the spec binds last`(@TempDir workspace: Path) {
        // The binding table is read `ORDER BY id` and the harness merge keeps the last write per
        // name, so "last wins" is the answer the registry gives the model. The repository has to
        // agree, otherwise the prompt lists one skill and `load_skill` fetches another.
        val agent = buildAgent(
            workspace,
            skill("report", "# first"),
            skill("report", "# second"),
        )

        val delivered = agent.skillRepositories.filter { it.source == "in-memory" }
        assertEquals(listOf("report"), delivered.flatMap { it.allSkillNames })

        val resolved = delivered.single().getSkill("report")
        assertEquals("# second", resolved.skillContent)
    }
}
