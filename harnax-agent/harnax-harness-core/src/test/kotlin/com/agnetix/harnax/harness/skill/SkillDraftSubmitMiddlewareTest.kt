package com.agnetix.harnax.harness.skill

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.middleware.AgentInput
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.model.FileInfo
import io.agentscope.harness.agent.filesystem.model.GlobResult
import io.agentscope.harness.agent.skill.curator.SkillPromoter
import io.agentscope.harness.agent.workspace.WorkspaceManager
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.quality.Strictness
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.function.Function

/**
 * The turn-end offer that keeps Admin's queue fed.
 *
 * Nothing upstream calls the promotion pipeline once a draft is staged, so this is the only thing that moves a
 * draft out of a session's workspace and into a reviewer's list. Two properties matter more than the happy
 * path: a draft already offered stays offered exactly once per window, because every later turn would otherwise
 * re-scan and re-post it, and a pipeline that fails must never be visible in the answer — the offer runs after
 * the stream has produced everything the user is waiting for, and it has no way to fix what broke.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillDraftSubmitMiddlewareTest {

    @Mock
    private lateinit var agent: HarnessAgent

    @Mock
    private lateinit var workspaceManager: WorkspaceManager

    @Mock
    private lateinit var filesystem: AbstractFilesystem

    private val ctx = RuntimeContext.empty()
    private var now = 1_000_000L

    private fun staged(vararg names: String): SkillDraftStaging {
        `when`(agent.workspaceManager).thenReturn(workspaceManager)
        `when`(workspaceManager.filesystem).thenReturn(filesystem)
        val matches = names.map { FileInfo.ofFile("$DRAFTS/$it/SKILL.md", 1L, MODIFIED) }
        `when`(filesystem.glob(anyOrNull(), eq("SKILL.md"), eq(DRAFTS))).thenReturn(GlobResult.success(matches))
        val deferred = SkillPromoter.PromotionResult.deferred("queued for human review", Duration.ofHours(24))
        `when`(agent.promoteSkill(any(), any(), anyOrNull())).thenReturn(Mono.just(deferred))
        return SkillDraftStaging().also { it.bind(agent) }
    }

    private fun middleware(staging: SkillDraftStaging) = SkillDraftSubmitMiddleware(
        staging = staging,
        cooldownMillis = COOLDOWN,
        clock = { now },
        scheduler = Schedulers.immediate(),
    )

    private fun SkillDraftSubmitMiddleware.turn() {
        onAgent(agent, ctx, AgentInput(emptyList()), Function { Flux.empty<AgentEvent>() }).blockLast()
    }

    @Test
    fun `a draft staged during the turn reaches the promotion pipeline`() {
        middleware(staged("invoice-fill")).turn()

        verify(agent).promoteSkill(eq("invoice-fill"), eq("system"), anyOrNull())
    }

    @Test
    fun `every draft staged is offered, not only the first`() {
        middleware(staged("invoice-fill", "weekly-report")).turn()

        verify(agent).promoteSkill(eq("invoice-fill"), any(), anyOrNull())
        verify(agent).promoteSkill(eq("weekly-report"), any(), anyOrNull())
    }

    @Test
    fun `a draft already offered inside the window is not offered again`() {
        val turn = middleware(staged("invoice-fill"))

        turn.turn()
        now += COOLDOWN - 1
        turn.turn()

        verify(agent, times(1)).promoteSkill(eq("invoice-fill"), any(), anyOrNull())
    }

    @Test
    fun `the same draft is offered again once the window has passed`() {
        val turn = middleware(staged("invoice-fill"))

        turn.turn()
        now += COOLDOWN
        turn.turn()

        verify(agent, times(2)).promoteSkill(eq("invoice-fill"), any(), anyOrNull())
    }

    @Test
    fun `an empty staging starts no promotion`() {
        middleware(staged()).turn()

        verify(agent, never()).promoteSkill(any(), any(), anyOrNull())
    }

    @Test
    fun `a staging not bound to an agent has nothing to offer`() {
        middleware(SkillDraftStaging()).turn()

        verifyNoInteractions(agent)
    }

    @Test
    fun `a pipeline that cannot start does not touch the answer`() {
        val staging = staged("invoice-fill")
        `when`(agent.promoteSkill(any(), any(), anyOrNull())).thenThrow(IllegalStateException("workspace gone"))

        assertDoesNotThrow { middleware(staging).turn() }
    }

    @Test
    fun `a pipeline that fails after the answer is logged not rethrown`() {
        val staging = staged("invoice-fill")
        `when`(agent.promoteSkill(any(), any(), anyOrNull()))
            .thenReturn(Mono.error(RuntimeException("admin unreachable")))

        assertDoesNotThrow { middleware(staging).turn() }
    }

    private companion object {
        const val DRAFTS = SkillDraftStaging.DRAFTS_DIR
        const val MODIFIED = "2026-10-05T00:00:00Z"
        const val COOLDOWN = 60_000L
    }
}
