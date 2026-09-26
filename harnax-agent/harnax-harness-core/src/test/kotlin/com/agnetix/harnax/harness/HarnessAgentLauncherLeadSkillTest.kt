package com.agnetix.harnax.harness

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import com.agnetix.harnax.entity.dto.AgentSpecInfoResponse
import com.agnetix.harnax.harness.sandbox.VirtualSandbox
import com.agnetix.harnax.harness.team.TeamOrchestrator
import com.agnetix.harnax.harness.team.TeamRuntimeSpec
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import io.agentscope.core.skill.AgentSkill
import io.agentscope.core.state.AgentStateStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import java.nio.file.Path
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * A team's lead has skills of its own now: the team's configuration binds them (design D5), and assembly
 * must let them through like any other agent's. Asserted on the built agent because which repositories
 * answer `load_skill` at model time is a property of that agent, not of the builder on the way there.
 *
 * A lead gets a keep-alive container like any other agent, so its skill's files are projected into it —
 * see [HarnessAgentWrapper.projectSkills]. What it has no way to do is *run* them: `disableShellTool()`
 * leaves it with no shell and the harness with no `<files-root>` to advertise, and the launcher reports
 * exactly that. Both halves are asserted here, because the file landing on disk and the warning about
 * being unable to execute it are the same decision seen from two sides.
 */
class HarnessAgentLauncherLeadSkillTest {

    private fun skill(
        name: String,
        content: String,
        resources: Map<String, String> = emptyMap(),
    ) = AgentSkill.builder()
        .name(name)
        .description("description of $name")
        .skillContent(content)
        .resources(resources)
        .build()

    private fun leadSpec(skillId: Long) = AgentSpec.builder()
        .id(0L)
        .name("Research")
        .description("the team")
        .systemPrompt("coordinate")
        .chatModelId(100L)
        .addSkill(SkillSpec(skillId = skillId, skillName = "report-style"))
        .build()

    private fun leadLauncher(skillAdaptor: SkillAdaptor): HarnessAgentLauncher = HarnessAgentLauncher(
        chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
        mcpConfigAdaptor = McpConfigAdaptor { null },
        stateStore = mock(AgentStateStore::class.java),
        skillAdaptor = skillAdaptor,
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = mock(ProcessLogAdaptor::class.java),
        toolCallLogAdaptor = mock(ToolCallLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")),
    )

    private fun leadOrchestrator(): TeamOrchestrator {
        val orchestrator = mock(TeamOrchestrator::class.java)
        `when`(orchestrator.spec).thenReturn(
            TeamRuntimeSpec(
                teamId = 7L,
                tenantId = 1L,
                teamName = "Research",
                rootSessionId = "web-team",
                leadAgentSpec = leadSpec(5L),
                leadChatSpec = ChatSpec.builder().build(),
                leadSpecInfo = AgentSpecInfoResponse(
                    agentId = 0L,
                    agentName = "Research",
                    description = "the team",
                    systemPrompt = "coordinate",
                    modelId = 100L,
                ),
                members = emptyList(),
            ),
        )
        return orchestrator
    }

    /** The lead built in [block], with everything this launcher warned about while it was being built. */
    private fun buildLeadReportingWarnings(
        workspace: Path,
        delivered: AgentSkill,
    ): Pair<HarnessAgentWrapper, List<String>> {
        val logger = LoggerFactory.getLogger(HarnessAgentLauncher::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.WARN
        try {
            val agent = leadLauncher(SkillAdaptor { delivered }).createTeamLead(
                orchestrator = leadOrchestrator(),
                agentSpec = leadSpec(5L),
                sessionId = "web-team",
                chatSpec = ChatSpec.builder().build(),
                userIdentifier = UserIdentifier(userId = 1L),
            )
            return agent to appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }

    private fun inMemorySkills(agent: HarnessAgentWrapper) = agent.harnessAgent.skillRepositories.filter { it.source == "in-memory" }

    /** What the wrapper would write into the session's container before the agent runs. */
    private fun projectSkills(agent: HarnessAgentWrapper): VirtualSandbox = VirtualSandbox().also { agent.projectSkills(it) }

    /** The projected files, without the projector's own bookkeeping file. */
    private fun skillFiles(sandbox: VirtualSandbox) = sandbox.files.filterKeys { !it.endsWith("/.harnax-skills.json") }

    @Test
    fun `the lead loads the skill the team binds`(@TempDir workspace: Path) {
        val (agent, warnings) = buildLeadReportingWarnings(workspace, skill("report-style", "# house style"))

        val delivered = inMemorySkills(agent)
        assertEquals(listOf("report-style"), delivered.flatMap { it.allSkillNames })
        assertEquals("# house style", delivered.single().getSkill("report-style").skillContent)
        // Nothing was given up, so nothing should be reported: an alarm on every lead build teaches
        // nobody to read it.
        assertTrue(warnings.none { it.contains("report-style") }, "unexpected warning: $warnings")

        assertEquals(
            mapOf("/workspace/skills/report-style/SKILL.md" to "# house style"),
            skillFiles(projectSkills(agent)),
            "the skill text is delivered, so its file should be too",
        )
    }

    @Test
    fun `a skill carrying files reaches the lead's container, with its missing shell reported`(@TempDir workspace: Path) {
        // The files are projected like any member's; what a lead cannot do is execute them, and the
        // launcher says so rather than letting the team look like it has a capability it cannot use.
        val (agent, warnings) = buildLeadReportingWarnings(
            workspace,
            skill("report-style", "# house style", mapOf("scripts/run.sh" to "#!/bin/sh")),
        )

        val delivered = inMemorySkills(agent)
        assertEquals("# house style", delivered.single().getSkill("report-style").skillContent)

        assertEquals(
            mapOf(
                "/workspace/skills/report-style/SKILL.md" to "# house style",
                "/workspace/skills/report-style/scripts/run.sh" to "#!/bin/sh",
            ),
            skillFiles(projectSkills(agent)),
        )

        val reported = warnings.single { it.contains("report-style") }
        assertTrue(reported.contains("scripts/run.sh"), "the unusable file is not named: $reported")
    }
}
