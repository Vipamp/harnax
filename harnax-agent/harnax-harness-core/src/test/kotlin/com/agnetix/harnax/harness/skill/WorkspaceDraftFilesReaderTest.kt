package com.agnetix.harnax.harness.skill

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.model.FileData
import io.agentscope.harness.agent.filesystem.model.FileInfo
import io.agentscope.harness.agent.filesystem.model.GlobResult
import io.agentscope.harness.agent.filesystem.model.ReadResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness

/**
 * The copy of a draft's files the review queue is built from.
 *
 * Two things have to hold or the queue describes a skill nobody approved. The keys keep their support
 * directory — Admin derives the script previews, and therefore the hashes a reviewer compares against the
 * installed bytes, from paths that start with `scripts/` — and a directory that will not list does not take
 * the other three down with it.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceDraftFilesReaderTest {

    @Mock
    private lateinit var filesystem: AbstractFilesystem

    private val ctx = RuntimeContext.empty()

    @BeforeEach
    fun emptyDraftDirs() {
        for (dir in SUPPORT_DIRS) {
            `when`(filesystem.glob(any(), anyString(), eq("$DRAFTS/invoice-fill/$dir"))).thenReturn(
                GlobResult.fail("no such directory"),
            )
        }
    }

    private fun reader(): WorkspaceDraftFilesReader = WorkspaceDraftFilesReader(filesystem, DRAFTS)

    private fun read(
        skillName: String = "invoice-fill",
        context: RuntimeContext? = ctx,
    ): Map<String, String> = reader().read(skillName, context)

    private fun stubDir(
        dir: String,
        vararg files: Pair<String, String>,
    ) {
        val matches = files.map { (rel, _) ->
            FileInfo.ofFile("$DRAFTS/invoice-fill/$dir/$rel", 1L, "2026-10-05T00:00:00Z")
        }
        `when`(filesystem.glob(any(), anyString(), eq("$DRAFTS/invoice-fill/$dir"))).thenReturn(GlobResult.success(matches))
        for ((rel, body) in files) {
            `when`(filesystem.read(any(), eq("$DRAFTS/invoice-fill/$dir/$rel"), anyInt(), anyInt())).thenReturn(
                ReadResult.success(FileData(body, "utf-8")),
            )
        }
    }

    @Test
    fun `a script is filed under the path the scanner reported it at`() {
        stubDir("scripts", "run.sh" to "echo first\nsecond line\n")

        val files = read()

        assertEquals(mapOf("scripts/run.sh" to "echo first\nsecond line\n"), files)
        assertTrue(files.getValue("scripts/run.sh").contains("second line"), "the body is stored whole, not as a head")
    }

    @Test
    fun `every support directory upstream scans is collected`() {
        stubDir("scripts", "run.sh" to "s")
        stubDir("references", "spec.md" to "r")
        stubDir("templates", "form.tpl" to "t")
        stubDir("assets", "logo.txt" to "a")

        val files = read()

        assertEquals(
            setOf("scripts/run.sh", "references/spec.md", "templates/form.tpl", "assets/logo.txt"),
            files.keys,
        )
    }

    @Test
    fun `a directory that will not list costs only that directory`() {
        stubDir("scripts", "run.sh" to "echo")
        `when`(filesystem.glob(any(), anyString(), eq("$DRAFTS/invoice-fill/references"))).thenThrow(
            RuntimeException("sandbox is gone"),
        )

        val files = read()

        assertEquals(mapOf("scripts/run.sh" to "echo"), files, "one unreadable directory must not lose the draft")
    }

    @Test
    fun `a file that will not read costs only that file`() {
        stubDir("scripts", "run.sh" to "echo", "other.sh" to "pwd")
        `when`(filesystem.read(any(), eq("$DRAFTS/invoice-fill/scripts/other.sh"), anyInt(), anyInt())).thenReturn(
            ReadResult.fail("too large"),
        )

        assertEquals(mapOf("scripts/run.sh" to "echo"), read())
    }

    @Test
    fun `a glob match without the directory segment is dropped rather than filed under a wrong path`() {
        // A filesystem that answers with a path the caller cannot attribute to a support directory would
        // otherwise put a body in the queue under a key that no promotion would ever write.
        `when`(filesystem.glob(any(), anyString(), eq("$DRAFTS/invoice-fill/scripts"))).thenReturn(
            GlobResult.success(listOf(FileInfo.ofFile("/tmp/elsewhere/run.sh", 1L, "2026-10-05T00:00:00Z"))),
        )

        assertTrue(read().isEmpty())
    }

    @Test
    fun `windows separators still produce a slash path`() {
        `when`(filesystem.glob(any(), anyString(), eq("$DRAFTS/invoice-fill/scripts"))).thenReturn(
            GlobResult.success(listOf(FileInfo.ofFile("$DRAFTS\\invoice-fill\\scripts\\run.sh", 1L, "2026"))),
        )
        `when`(filesystem.read(any(), anyString(), anyInt(), anyInt())).thenReturn(
            ReadResult.success(FileData("echo", "utf-8")),
        )

        assertEquals(setOf("scripts/run.sh"), read().keys)
    }

    @Test
    fun `a review with no context still reads from the workspace`() {
        stubDir("scripts", "run.sh" to "echo")

        assertEquals(mapOf("scripts/run.sh" to "echo"), read(context = null))
        // The reader replaces a missing context rather than passing it down, because the filesystem resolves
        // the workspace namespace from it. `RuntimeContext.empty()` is a new instance every call, so the
        // question is only that one arrived.
        val seen = argumentCaptor<RuntimeContext>()
        verify(filesystem).glob(seen.capture(), anyString(), eq("$DRAFTS/invoice-fill/scripts"))
        assertNotNull(seen.firstValue)
    }

    @Test
    fun `every staged draft is listed by its own name`() {
        stubListing("invoice-fill", "weekly-report")

        assertEquals(listOf("invoice-fill", "weekly-report"), reader().listDraftSkillNames(ctx))
    }

    @Test
    fun `a draft the agent deleted is not offered as a proposal`() {
        // Deletion is a move to `.archive/<name>-<ts>/`, one level below the staging directory. Listing it
        // would start a promotion for a skill that is already gone from the live set.
        stubListing(".archive/invoice-fill-1728000000")

        assertTrue(reader().listDraftSkillNames(ctx).isEmpty())
    }

    @Test
    fun `a staging area that will not list reads as no drafts`() {
        `when`(filesystem.glob(any(), eq("SKILL.md"), eq(DRAFTS))).thenThrow(RuntimeException("sandbox is gone"))

        assertTrue(reader().listDraftSkillNames(ctx).isEmpty())
    }

    private fun stubListing(vararg names: String) {
        val matches = names.map { FileInfo.ofFile("$DRAFTS/$it/SKILL.md", 1L, "2026-10-05T00:00:00Z") }
        `when`(filesystem.glob(any(), eq("SKILL.md"), eq(DRAFTS))).thenReturn(GlobResult.success(matches))
    }

    private companion object {
        private const val DRAFTS = "skills/_drafts"
        private val SUPPORT_DIRS = listOf("scripts", "references", "templates", "assets")
    }
}
