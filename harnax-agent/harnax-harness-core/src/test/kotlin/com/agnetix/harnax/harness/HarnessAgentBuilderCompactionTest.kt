package com.agnetix.harnax.harness

import com.agnetix.harnax.harness.compaction.AutoCompactionTier
import io.agentscope.core.model.ChatModelBase
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.memory.compaction.CompactionConfig
import io.agentscope.harness.agent.middleware.CompactionMiddleware
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Path

/**
 * Whether the built agent runs on harnax's pinned tier or on the defaults agentscope 2.0.4 picks for itself.
 *
 * The tier a middleware consults is a private final field (upstream `CompactionMiddleware.java:66`) and the
 * middleware list is where the assembled agent actually carries it, so both the premise and the wiring are
 * read off the composed agent rather than off the builder on the way there. Same reason the memory gate test
 * reads private fields: the honest answer to "which tier did this agent get" is the field the hook will
 * consult, not the line logged about it.
 */
class HarnessAgentBuilderCompactionTest {

    private fun buildAgent(workspace: Path, tier: CompactionConfig?) = HarnessAgentBuilder()
        .name("tester")
        .description("tester")
        .maxIters(1)
        .systemPrompt("prompt")
        .model(mock(ChatModelBase::class.java))
        .workspace(workspace)
        .apply { tier?.let { compaction(it) } }
        .build()

    /** The tier the installed hook will read, whichever position upstream put the middleware in. */
    private fun installedConfig(agent: HarnessAgent): CompactionConfig {
        val hook = agent.delegate.middlewares.filterIsInstance<CompactionMiddleware>().single()
        return privateField(hook, "config") as CompactionConfig
    }

    private fun privateField(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    @Test
    fun `upstream installs its own defaults when nothing pins a tier`(@TempDir workspace: Path) {
        // The premise this file exists to keep honest: 2.0.4 builds a default config on its own
        // (HarnessAgent.java:1227), so a passthrough that never reaches the builder would still leave the
        // middleware installed — with the session copy this runtime wants off.
        val config = installedConfig(buildAgent(workspace, tier = null))

        assertTrue(config.isOffloadBeforeCompact, "agentscope 2.0.4 is expected to default the session copy on")
        assertEquals(50, config.triggerMessages)
    }

    @Test
    fun `compaction pins the tier the assembled agent runs on`(@TempDir workspace: Path) {
        val config = installedConfig(buildAgent(workspace, tier = AutoCompactionTier.auto()))

        assertFalse(config.isOffloadBeforeCompact, "harnax pins the automatic tier without the session copy")
        assertEquals(20_000, config.reserved)
        assertEquals(50, config.triggerMessages)
    }

    @Test
    fun `pinning a tier does not turn automatic compaction off`(@TempDir workspace: Path) {
        // Upstream reads a null config as "disable the middleware entirely" (HarnessAgent.java:1875), so
        // passing a tier must not be mistaken for passing nothing.
        assertNotNull(buildAgent(workspace, tier = AutoCompactionTier.auto()).getCompactionHook())
    }
}
