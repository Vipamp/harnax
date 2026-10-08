package com.agnetix.harnax.harness

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.harness.compaction.AutoCompactionTier
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.Memory
import com.agnetix.harnax.harness.config.MinioConfig
import com.agnetix.harnax.harness.config.SandboxConfig
import com.agnetix.harnax.tools.sdk.UserIdentifier
import io.agentscope.core.state.AgentStateStore
import io.agentscope.harness.agent.memory.compaction.CompactionConfig
import io.agentscope.harness.agent.middleware.CompactionMiddleware
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Path

/**
 * The tier a real assembled agent gets, read off the middleware it will actually run.
 *
 * The builder passthrough has its own test; this covers the other half — that the launcher calls it. A
 * passthrough nobody invokes leaves every session on upstream's defaults, including the session copy harnax
 * has no reader for.
 */
class HarnessAgentLauncherCompactionTest {

    // This module has no shared launcher fixture, so each launcher test carries its own construction helpers;
    // these are the ones HarnessAgentLauncherMemoryTest.kt:68-136 uses.

    private fun launcher(workspaceRoot: Path): HarnessAgentLauncher = HarnessAgentLauncher(
        chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
        mcpConfigAdaptor = McpConfigAdaptor { null },
        stateStore = mock(AgentStateStore::class.java),
        skillAdaptor = mock(SkillAdaptor::class.java),
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = mock(ProcessLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = workspaceRoot,
        harnessConfig = HarnessConfig(
            sandbox = SandboxConfig(enabled = false),
            memory = Memory(enabled = false),
        ),
        minioConfig = MinioConfig(
            endpoint = "http://127.0.0.1:1",
            accessKey = "minioadmin",
            secretKey = "minioadmin",
        ),
        snapshotSpec = null,
    )

    private fun spec() = AgentSpec.builder()
        .id(1L)
        .tenantId(4L)
        .name("Research")
        .description("a research agent")
        .systemPrompt("answer")
        .chatModelId(100L)
        .memoryEnabled(false)
        .build()

    private fun build(workspaceRoot: Path): HarnessAgentWrapper = launcher(workspaceRoot).createSingleAgent(
        agentSpec = spec(),
        sessionId = "sess-1",
        chatSpec = ChatSpec.builder().build(),
        userIdentifier = UserIdentifier(userId = 1L),
    )

    /** Upstream keeps the field private and picks the tier itself, so the honest read is the hook's own. */
    private fun privateField(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun installedConfig(wrapper: HarnessAgentWrapper): CompactionConfig {
        val hook = wrapper.harnessAgent.delegate.middlewares.filterIsInstance<CompactionMiddleware>().single()
        return privateField(hook, "config") as CompactionConfig
    }

    @Test
    fun `the launcher pins the automatic tier onto every assembled agent`(@TempDir workspace: Path) {
        val config = installedConfig(build(workspace))

        assertFalse(
            config.isOffloadBeforeCompact,
            "the offload copy is the transcript channel disableTranscript() already turns off, from the other side",
        )
        assertEquals(AutoCompactionTier.RESERVED_TOKENS, config.reserved)
        assertEquals(AutoCompactionTier.TRIGGER_MESSAGES, config.triggerMessages)
    }
}
