package com.agnetix.harnax.harness.skill

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.model.FileData
import io.agentscope.harness.agent.filesystem.model.FileInfo
import io.agentscope.harness.agent.filesystem.model.GlobResult
import io.agentscope.harness.agent.filesystem.model.ReadResult
import io.agentscope.harness.agent.skill.curator.SkillPromoter
import io.agentscope.harness.agent.workspace.WorkspaceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * The handle the draft pipeline holds before the agent it points at exists.
 *
 * The gate and the offering middleware are built before `build()` and can only reach the workspace after it,
 * so the two states on either side of [SkillDraftStaging.bind] are both load-bearing: an unbound staging must
 * read as empty rather than throw — a half-assembled agent must still answer — and a bound one must go to the
 * filesystem the harness itself built, not to any other.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillDraftStagingTest {

    @Mock
    private lateinit var agent: HarnessAgent

    @Mock
    private lateinit var workspaceManager: WorkspaceManager

    @Mock
    private lateinit var filesystem: AbstractFilesystem

    private val ctx = RuntimeContext.empty()

    private fun bound(vararg staged: Pair<String, String>): SkillDraftStaging {
        `when`(agent.workspaceManager).thenReturn(workspaceManager)
        `when`(workspaceManager.filesystem).thenReturn(filesystem)
        val matches = staged.map { (name, _) ->
            FileInfo.ofFile("${SkillDraftStaging.DRAFTS_DIR}/$name/SKILL.md", 1L, MODIFIED)
        }
        `when`(
            filesystem.glob(
                anyOrNull(),
                eq("SKILL.md"),
                eq(SkillDraftStaging.DRAFTS_DIR),
            ),
        ).thenReturn(GlobResult.success(matches))
        for ((name, body) in staged) {
            `when`(
                filesystem.glob(
                    anyOrNull(),
                    eq("*"),
                    eq("${SkillDraftStaging.DRAFTS_DIR}/$name/scripts"),
                ),
            ).thenReturn(
                GlobResult.success(
                    listOf(FileInfo.ofFile("${SkillDraftStaging.DRAFTS_DIR}/$name/scripts/run.sh", 1L, MODIFIED)),
                ),
            )
            `when`(
                filesystem.read(
                    anyOrNull(),
                    eq("${SkillDraftStaging.DRAFTS_DIR}/$name/scripts/run.sh"),
                    anyInt(),
                    anyInt(),
                ),
            ).thenReturn(ReadResult.success(FileData(body, "utf-8")))
        }
        return SkillDraftStaging().also { it.bind(agent) }
    }

    @Test
    fun `an unbound staging has nothing to show`() {
        val staging = SkillDraftStaging()

        assertTrue(staging.listDraftNames(ctx).isEmpty())
        assertTrue(staging.read("invoice-fill", ctx).isEmpty())
        assertNull(staging.promote("invoice-fill", "system", ctx))
        verify(agent, never()).promoteSkill(any(), any(), anyOrNull())
    }

    @Test
    fun `a draft the harness staged is listed by name`() {
        val staging = bound("invoice-fill" to "echo first\n")

        assertEquals(listOf("invoice-fill"), staging.listDraftNames(ctx))
    }

    @Test
    fun `a support file is read off the filesystem the agent was built with`() {
        val staging = bound("invoice-fill" to "echo first\n")

        assertEquals(mapOf("scripts/run.sh" to "echo first\n"), staging.read("invoice-fill", ctx))
    }

    @Test
    fun `offering a draft runs the promotion pipeline on the bound agent`() {
        val staged = bound("invoice-fill" to "echo first\n")
        val deferred = SkillPromoter.PromotionResult.deferred("queued for human review", Duration.ofHours(24))
        `when`(agent.promoteSkill(any(), any(), anyOrNull())).thenReturn(Mono.just(deferred))

        val promotion = staged.promote("invoice-fill", "system", ctx)

        assertEquals(deferred, promotion?.block())
        verify(agent).promoteSkill(eq("invoice-fill"), eq("system"), anyOrNull())
    }

    @Test
    fun `an agent with no workspace filesystem lists nothing but is still the one that promotes`() {
        // Nothing to list means nothing gets offered on its own. The pipeline is reached through the agent
        // rather than through the directory handle, so a promotion started from elsewhere is not lost.
        `when`(agent.workspaceManager).thenReturn(null)
        val deferred = SkillPromoter.PromotionResult.deferred("queued for human review", Duration.ofHours(24))
        `when`(agent.promoteSkill(any(), any(), anyOrNull())).thenReturn(Mono.just(deferred))
        val staging = SkillDraftStaging().also { it.bind(agent) }

        assertTrue(staging.listDraftNames(ctx).isEmpty())
        assertEquals(deferred, staging.promote("invoice-fill", "system", ctx)?.block())
    }

    @Test
    fun `the session-enabled directory is one of the staged directories the guard covers`() {
        assertEquals(
            listOf(
                SkillDraftStaging.DRAFTS_DIR,
                SkillDraftStaging.PROMOTED_DIR,
                SkillDraftStaging.SESSION_ENABLED_DIR,
            ),
            SkillDraftStaging.STAGING_DIRS,
        )
        assertEquals("harnax-skill-staging/session-enabled", SkillDraftStaging.SESSION_ENABLED_DIR)
        SkillDraftStaging.STAGING_DIRS.forEach {
            assertFalse(dirClashesWithSkillsDir(it), "'$it' would land inside the delivered-skills directory")
        }
    }

    @Test
    fun `the guard's own rule still rejects a directory under the delivered skills`() {
        assertTrue(dirClashesWithSkillsDir("skills/session-enabled"))
        assertTrue(dirClashesWithSkillsDir("skills"))
        assertFalse(dirClashesWithSkillsDir("harnax-skill-staging/session-enabled/inner"))
    }

    private companion object {
        const val MODIFIED = "2026-10-05T00:00:00Z"
    }
}
