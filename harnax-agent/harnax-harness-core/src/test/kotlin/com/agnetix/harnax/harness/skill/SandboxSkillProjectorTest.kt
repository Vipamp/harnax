package com.agnetix.harnax.harness.skill

import com.agnetix.harnax.harness.sandbox.VirtualSandbox
import io.agentscope.core.skill.AgentSkill
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Skill files have to reach the container, survive a reused container, and stay inside their own
 * directory. Asserted against a fake [io.agentscope.harness.agent.sandbox.Sandbox] that records every
 * `exec` and models the resulting filesystem, so both halves — the commands issued and the state they
 * leave behind — are pinned. Mockito would only record the calls here, not what they mean.
 */
class SandboxSkillProjectorTest {

    private val workspaceRoot = "/workspace"

    private val skillsRoot = "$workspaceRoot/skills"

    private val manifestPath = "$skillsRoot/.harnax-skills.json"

    private fun skill(
        name: String,
        content: String = "# $name",
        resources: Map<String, String> = emptyMap(),
    ) = AgentSkill.builder()
        .name(name)
        .description("description of $name")
        .skillContent(content)
        .resources(resources)
        .build()

    /** Absolute paths written by the commands issued after the turn marker, i.e. one per write. */
    private fun VirtualSandbox.decodeTargets(from: Int): List<String> = commands.drop(from).mapNotNull { DECODE_TARGET.find(it)?.groupValues?.get(1) }

    @Test
    fun `the first turn lays every delivered file under the skills root`() {
        val sandbox = VirtualSandbox()

        val result = SandboxSkillProjector(workspaceRoot).project(
            sandbox,
            listOf(
                skill(
                    "report-style",
                    "# house style",
                    mapOf("scripts/run.sh" to "#!/bin/sh", "templates/weekly.md" to "# weekly"),
                ),
            ),
        )

        assertEquals(
            mapOf(
                "$skillsRoot/report-style/SKILL.md" to "# house style",
                "$skillsRoot/report-style/scripts/run.sh" to "#!/bin/sh",
                "$skillsRoot/report-style/templates/weekly.md" to "# weekly",
            ),
            sandbox.files.filterKeys { it != manifestPath },
        )
        assertEquals(
            setOf("report-style/SKILL.md", "report-style/scripts/run.sh", "report-style/templates/weekly.md"),
            result.written.toSet(),
        )
        assertTrue(sandbox.files.getValue(manifestPath).contains("report-style/scripts/run.sh"))
    }

    @Test
    fun `an unchanged next turn reads the manifest and writes nothing`() {
        val projector = SandboxSkillProjector(workspaceRoot)
        val sandbox = VirtualSandbox()
        val skills = listOf(skill("report-style", "# house style", mapOf("scripts/run.sh" to "#!/bin/sh")))
        projector.project(sandbox, skills)

        val turnStart = sandbox.commands.size
        val filesBefore = sandbox.files.toMap()
        val second = projector.project(sandbox, skills)

        assertEquals(
            listOf("base64 '$manifestPath'"),
            sandbox.commands.subList(turnStart, sandbox.commands.size),
            "a converged turn must not touch the container beyond reading its own manifest",
        )
        assertEquals(filesBefore, sandbox.files)
        assertFalse(second.changed)
        assertEquals(2, second.unchangedFiles)
    }

    @Test
    fun `a changed skill is the only thing rewritten`() {
        val projector = SandboxSkillProjector(workspaceRoot)
        val sandbox = VirtualSandbox()
        projector.project(sandbox, listOf(skill("report-style", "# house style"), skill("weekly", "# weekly")))

        val turnStart = sandbox.commands.size
        val result = projector.project(
            sandbox,
            listOf(skill("report-style", "# house style, revised"), skill("weekly", "# weekly")),
        )

        assertEquals(
            listOf("$skillsRoot/report-style/SKILL.md", manifestPath),
            sandbox.decodeTargets(turnStart),
        )
        assertEquals(listOf("report-style/SKILL.md"), result.written)
        assertEquals("# house style, revised", sandbox.files.getValue("$skillsRoot/report-style/SKILL.md"))
        assertEquals("# weekly", sandbox.files.getValue("$skillsRoot/weekly/SKILL.md"))
    }

    @Test
    fun `a skill that is no longer bound loses its whole directory`() {
        val projector = SandboxSkillProjector(workspaceRoot)
        val sandbox = VirtualSandbox()
        projector.project(
            sandbox,
            listOf(skill("kept"), skill("gone", resources = mapOf("scripts/run.sh" to "#!/bin/sh"))),
        )
        // The agent may have written beside its skills; that is not this projection's to clean up.
        sandbox.files["$skillsRoot/notes.md"] = "written by the agent itself"

        val result = projector.project(sandbox, listOf(skill("kept")))

        assertEquals(listOf("gone/"), result.deleted)
        assertTrue(sandbox.removedDirectories.contains("$skillsRoot/gone"), "${sandbox.removedDirectories}")
        assertTrue(sandbox.pathsUnder("$skillsRoot/gone").isEmpty())
        assertEquals("# kept", sandbox.files.getValue("$skillsRoot/kept/SKILL.md"))
        assertEquals("written by the agent itself", sandbox.files.getValue("$skillsRoot/notes.md"))
        assertTrue(result.written.isEmpty(), "nothing new was delivered, so nothing was rewritten: ${result.written}")
    }

    @Test
    fun `unbinding the last skill empties the skills root`() {
        val projector = SandboxSkillProjector(workspaceRoot)
        val sandbox = VirtualSandbox()
        projector.project(sandbox, listOf(skill("report-style")))

        val result = projector.project(sandbox, emptyList())

        assertEquals(listOf("report-style/"), result.deleted)
        assertTrue(sandbox.pathsUnder(skillsRoot).isEmpty(), sandbox.files.keys.toString())
    }

    @Test
    fun `a first turn with nothing to deliver leaves the container alone`() {
        val sandbox = VirtualSandbox()

        val result = SandboxSkillProjector(workspaceRoot).project(sandbox, emptyList())

        assertEquals(SandboxSkillProjector.ProjectionResult.EMPTY, result)
        assertEquals(listOf("base64 '$manifestPath'"), sandbox.commands)
        assertTrue(sandbox.files.isEmpty())
    }

    @Test
    fun `a resource that leaves its skill directory is refused instead of written`() {
        val sandbox = VirtualSandbox()

        val result = SandboxSkillProjector(workspaceRoot).project(
            sandbox,
            listOf(
                skill(
                    "report-style",
                    "# house style",
                    mapOf(
                        "../escape.sh" to "rm -rf /",
                        "/etc/cron.d/evil" to "evil",
                        "SKILL.md" to "not the skill text",
                        "./scripts/run.sh" to "#!/bin/sh",
                    ),
                ),
            ),
        )

        assertEquals(setOf("report-style/SKILL.md", "report-style/scripts/run.sh"), result.written.toSet())
        assertEquals(3, result.rejected.size, result.rejected.toString())
        listOf("../escape.sh", "/etc/cron.d/evil", "SKILL.md").forEach { key ->
            assertTrue(result.rejected.any { it.contains("'$key'") }, "$key was not reported: ${result.rejected}")
        }
        assertTrue(
            sandbox.pathsUnder(workspaceRoot).all { it.startsWith("$skillsRoot/") },
            "something was written outside the skills root: ${sandbox.files.keys}",
        )
        assertEquals("# house style", sandbox.files.getValue("$skillsRoot/report-style/SKILL.md"))
    }

    @Test
    fun `a skill that cannot name its own directory contributes nothing`() {
        val sandbox = VirtualSandbox()

        val result = SandboxSkillProjector(workspaceRoot).project(
            sandbox,
            listOf(skill("../escape"), skill("two/segments"), skill("no-text", content = "  ")),
        )

        assertTrue(result.written.isEmpty(), result.written.toString())
        assertEquals(3, result.rejected.size, result.rejected.toString())
        assertTrue(sandbox.files.isEmpty(), sandbox.files.keys.toString())
    }

    @Test
    fun `a container that refuses the write does not fail the turn`() {
        val sandbox = VirtualSandbox(failWhen = { it.startsWith("printf") })

        // The point of this test is that no exception escapes; a turn must survive a lost skill file.
        val result = SandboxSkillProjector(workspaceRoot).project(sandbox, listOf(skill("report-style")))

        assertTrue(result.written.isEmpty())
        assertFalse(sandbox.files.containsKey(manifestPath), "a half-written turn must not be recorded as done")
    }

    @Test
    fun `an unreadable manifest means writing everything again`() {
        val projector = SandboxSkillProjector(workspaceRoot)
        val sandbox = VirtualSandbox()
        projector.project(sandbox, listOf(skill("report-style", "# house style")))
        sandbox.files[manifestPath] = "not json"

        val result = projector.project(sandbox, listOf(skill("report-style", "# house style")))

        assertEquals(listOf("report-style/SKILL.md"), result.written)
        assertEquals("# house style", sandbox.files.getValue("$skillsRoot/report-style/SKILL.md"))
        assertTrue(sandbox.files.getValue(manifestPath).startsWith("{"), "the manifest was not repaired")
    }

    private companion object {
        val DECODE_TARGET = Regex("""base64 -d '[^']+' > '([^']+)'""")
    }
}
