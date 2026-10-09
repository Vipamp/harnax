package com.agnetix.harnax.harness.skill

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.model.FileData
import io.agentscope.harness.agent.filesystem.model.FileInfo
import io.agentscope.harness.agent.filesystem.model.GlobResult
import io.agentscope.harness.agent.filesystem.model.ReadResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq

/**
 * The catalog reads this repository once per call, so a skill enabled after a turn is in front of the model
 * on the next one without anything reopening the sandbox.
 */
class SessionEnabledSkillRepositoryTest {

    private val fs = Mockito.mock(AbstractFilesystem::class.java)

    private val dir = SkillDraftStaging.SESSION_ENABLED_DIR

    private fun repo(): SessionEnabledSkillRepository {
        val repo = SessionEnabledSkillRepository()
        repo.bindFilesystem(fs)
        return repo
    }

    private fun stubEnabled(name: String, body: String) {
        // The listing answers with paths relative to the workspace root, and the reader keeps only the two
        // segments that follow the staging directory — a bare `<name>/SKILL.md` is dropped, not parsed.
        val skillPath = "$dir/$name/SKILL.md"
        val listed = GlobResult.success(listOf(FileInfo.ofFile(skillPath, 1L, "2026-10-08T10:00:00Z")))
        Mockito.`when`(fs.glob(any(), eq("SKILL.md"), eq(dir))).thenReturn(listed)
        Mockito.`when`(fs.glob(any(), eq("SKILL.md"), eq("$dir/$name"))).thenReturn(listed)
        Mockito.`when`(fs.read(any(), eq(skillPath), anyInt(), anyInt()))
            .thenReturn(ReadResult.success(FileData(body, "utf-8")))
        listOf("scripts", "references", "templates", "assets").forEach {
            Mockito.`when`(fs.glob(any(), anyString(), eq("$dir/$name/$it"))).thenReturn(GlobResult.fail("none"))
        }
    }

    @Test
    fun `an enabled skill is loaded with the source that marks it as the session's own`() {
        stubEnabled("invoice-fill", "---\nname: invoice-fill\ndescription: fills\n---\nUse it.\n")
        val skills = repo().getAllSkills(RuntimeContext.empty())
        assertEquals(1, skills.size)
        assertEquals("invoice-fill", skills[0].name)
        assertEquals(SESSION_SKILL_SOURCE, skills[0].source)
        assertEquals(SESSION_SKILL_SOURCE, repo().source)
        assertFalse(repo().isWriteable, "the model must not be able to write into its own enabled area")
        assertEquals(listOf("invoice-fill"), repo().allSkillNames)
        assertTrue(repo().skillExists("invoice-fill"))
        assertFalse(repo().skillExists("other-skill"))
    }

    @Test
    fun `nothing is offered before the filesystem is bound`() {
        val repo = SessionEnabledSkillRepository()
        assertTrue(repo.getAllSkills(RuntimeContext.empty()).isEmpty())
        assertTrue(repo.allSkillNames.isEmpty())
    }

    @Test
    fun `a directory whose text no longer parses is skipped, not thrown`() {
        stubEnabled("broken", "no front matter at all")
        assertTrue(repo().getAllSkills(RuntimeContext.empty()).isEmpty())
    }
}
