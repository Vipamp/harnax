package com.agnetix.harnax.harness

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.Memory
import com.agnetix.harnax.harness.config.MinioConfig
import com.agnetix.harnax.harness.config.SandboxConfig
import com.agnetix.harnax.harness.minio.MinioBaseStore
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import io.agentscope.core.state.AgentStateStore
import io.agentscope.harness.agent.filesystem.CompositeFilesystem
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware
import io.agentscope.harness.agent.middleware.MemoryMaintenanceMiddleware
import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import java.nio.file.Path
import ch.qos.logback.classic.Logger as LogbackLogger

/**
 * The switch matrix, asserted on a built agent rather than on the builder on the way there.
 *
 * "Half-open" is the failure this domain has been living in: the four memory tools were advertised to
 * every model while the hooks that feed them were switched off, and the read side sat in a middleware
 * nobody installed. Each row here therefore reads two things at once — what the model was offered and
 * what the runtime actually installed — because a matrix checked against itself proves nothing.
 */
class HarnessAgentLauncherMemoryTest {

    private fun launcher(
        workspaceRoot: Path,
        memory: Memory,
        enableMemoryHooks: Boolean = false,
        enableWorkspaceContext: Boolean = false,
        sandboxEnabled: Boolean = false,
        minioConfig: MinioConfig? = minio(),
    ): HarnessAgentLauncher = HarnessAgentLauncher(
        chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
        mcpConfigAdaptor = McpConfigAdaptor { null },
        stateStore = mock(AgentStateStore::class.java),
        skillAdaptor = mock(SkillAdaptor::class.java),
        tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
        processLogAdaptor = mock(ProcessLogAdaptor::class.java),
        toolCallLogAdaptor = mock(ToolCallLogAdaptor::class.java),
        planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
        workspaceRoot = workspaceRoot,
        harnessConfig = HarnessConfig(
            sandbox = SandboxConfig(enabled = sandboxEnabled),
            enableWorkspaceContext = enableWorkspaceContext,
            enableMemoryHooks = enableMemoryHooks,
            memory = memory,
        ),
        minioConfig = minioConfig,
        snapshotSpec = if (sandboxEnabled) mock(SandboxSnapshotSpec::class.java) else null,
    )

    /** MinIO coordinates that never get contacted: the launcher only builds clients here. */
    private fun minio() = MinioConfig(
        endpoint = "http://127.0.0.1:1",
        accessKey = "minioadmin",
        secretKey = "minioadmin",
    )

    private fun spec() = AgentSpec.builder()
        .id(1L)
        .tenantId(4L)
        .name("Research")
        .description("a research agent")
        .systemPrompt("answer")
        .chatModelId(100L)
        .build()

    private fun build(
        workspaceRoot: Path,
        memory: Memory,
        enableMemoryHooks: Boolean = false,
        enableWorkspaceContext: Boolean = false,
        sandboxEnabled: Boolean = false,
        minioConfig: MinioConfig? = minio(),
        userId: Long? = 1L,
    ): HarnessAgentWrapper = launcher(
        workspaceRoot = workspaceRoot,
        memory = memory,
        enableMemoryHooks = enableMemoryHooks,
        enableWorkspaceContext = enableWorkspaceContext,
        sandboxEnabled = sandboxEnabled,
        minioConfig = minioConfig,
    ).createSingleAgent(
        agentSpec = spec(),
        sessionId = "sess-1",
        chatSpec = ChatSpec.builder().build(),
        userIdentifier = UserIdentifier(userId = userId),
    )

    private fun middlewares(agent: HarnessAgentWrapper) = agent.harnessAgent.delegate.middlewares

    private fun memoryTools(agent: HarnessAgentWrapper) = listOf("memory_search", "memory_get", "memory_save", "session_search")
        .filter { agent.harnessAgent.toolkit.getTool(it) != null }

    @Test
    fun `off means neither the tools nor the hooks`(@TempDir workspace: Path) {
        val agent = build(workspace, Memory(enabled = false))

        assertTrue(memoryTools(agent).isEmpty(), "an un-fed tool is an invitation to call something that cannot answer: ${memoryTools(agent)}")
        assertFalse(middlewares(agent).any { it is MemoryFlushMiddleware }, "the flush hook should not be installed")
        assertFalse(middlewares(agent).any { it is MemoryMaintenanceMiddleware }, "the maintenance hook should not be installed")
    }

    @Test
    fun `the hooks alone still write, but nothing is offered to the model`(@TempDir workspace: Path) {
        // The last row of the matrix: extraction runs because the operator left the hooks on, while no owner
        // bucket is mounted and no tool is offered. Whether those writes land in a bucket is measured against
        // a live store in `MemoryBucketPipelineTest` — upstream mounts a `MEMORY.md` route for the configured
        // filesystem either way, so nothing observable on an agent built against an unreachable store tells
        // the two mounts apart.
        val agent = build(workspace, Memory(enabled = false), enableMemoryHooks = true)

        assertTrue(middlewares(agent).any { it is MemoryFlushMiddleware }, "the hooks were asked for")
        assertTrue(middlewares(agent).any { it is MemoryMaintenanceMiddleware }, "the hooks were asked for")
        assertTrue(memoryTools(agent).isEmpty(), "an agent with memory off must not be taught to use it: ${memoryTools(agent)}")
    }

    @Test
    fun `on installs both hooks and offers the tools`(@TempDir workspace: Path) {
        val agent = build(workspace, Memory(enabled = true), enableMemoryHooks = true)

        assertTrue(middlewares(agent).any { it is MemoryFlushMiddleware }, "memory on without a flush hook writes nothing")
        assertTrue(middlewares(agent).any { it is MemoryMaintenanceMiddleware }, "memory on without maintenance never consolidates the ledger")
        assertTrue(memoryTools(agent).containsAll(listOf("memory_search", "memory_get", "memory_save")), "got ${memoryTools(agent)}")
        assertFalse(memoryTools(agent).contains("session_search"), "session_search reads the local transcript of one replica, not the owner's bucket")
    }

    @Test
    fun `on needs the workspace context even when that knob stayed off`(@TempDir workspace: Path) {
        // The read side is this middleware alone; without it the bucket is written and never injected.
        val agent = build(workspace, Memory(enabled = true), enableMemoryHooks = true, enableWorkspaceContext = false)

        assertTrue(middlewares(agent).any { it is WorkspaceContextMiddleware }, "memory on with no reader is half-open")
    }

    @Test
    fun `on without MinIO is refused rather than silently memoryless`(@TempDir workspace: Path) {
        val failure = assertThrows(IllegalStateException::class.java) {
            build(workspace, Memory(enabled = true), minioConfig = null)
        }

        assertTrue(failure.message!!.contains("MinIO"), failure.message!!)
    }

    @Test
    fun `on without the hooks is refused rather than half-open`(@TempDir workspace: Path) {
        val failure = assertThrows(IllegalStateException::class.java) {
            build(workspace, Memory(enabled = true), enableMemoryHooks = false)
        }

        assertTrue(failure.message!!.contains("harness.enable-memory-hooks"), failure.message!!)
    }

    @Test
    fun `the two memory files move to the owner bucket while everything else stays put`(@TempDir workspace: Path) {
        val agent = build(workspace, Memory(enabled = true), enableMemoryHooks = true)

        val composite = assertInstanceOf(
            CompositeFilesystem::class.java,
            agent.harnessAgent.workspaceManager.filesystem,
            "mounting a route puts a composite in front of the configured filesystem",
        )
        assertNotSame(
            composite.defaultBackend,
            composite.filesystemFor("MEMORY.md"),
            "MEMORY.md should answer from the owner bucket",
        )
        assertNotSame(
            composite.defaultBackend,
            composite.filesystemFor("memory/2026-10-05.md"),
            "the daily ledger too",
        )
        assertSame(
            composite.defaultBackend,
            composite.filesystemFor("agents/Research/sessions/sess-1/filesystem/notes.md"),
            "anything else keeps the isolation scope it has today",
        )
    }

    @Test
    fun `a sandbox deployment gets the same owner bucket for its memory`(@TempDir workspace: Path) {
        val agent = build(workspace, Memory(enabled = true), enableMemoryHooks = true, sandboxEnabled = true)

        val routed = assertInstanceOf(
            RoutedSandboxFilesystem::class.java,
            agent.harnessAgent.workspaceManager.filesystem,
            "with routes mounted the sandbox filesystem is wrapped, not replaced: shell_execute still targets the sandbox",
        )
        assertNotSame(routed.primary(), routed.backendFor("MEMORY.md"), "the owner bucket is mounted on this branch as well")
        assertNotSame(routed.primary(), routed.backendFor("memory/2026-10-05.md"))
        assertSame(routed.primary(), routed.backendFor("agents/Research/sessions/sess-1/filesystem/notes.md"), "and nothing else leaves the sandbox")
    }

    @Test
    fun `the consolidation gate is driven by the store that can compare-and-swap`(@TempDir workspace: Path) {
        val agent = build(workspace, Memory(enabled = true), enableMemoryHooks = true, sandboxEnabled = true)

        val store = agent.harnessAgent.distributedStore
        assertNotNull(store, "the sandbox branch is the one that gets a distributed store")
        assertInstanceOf(
            MinioBaseStore::class.java,
            store.baseStore(),
            "with no distributed store upstream picks LocalPeriodicGate, so the 30-minute consolidation bound " +
                "would be per replica rather than per owner bucket",
        )
    }

    /** [block] with every INFO and WARN the launcher emits while it runs. */
    private fun reporting(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(HarnessAgentLauncher::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        val previousLevel = logger.level
        logger.addAppender(appender)
        logger.level = Level.INFO
        try {
            block()
            return appender.list.filter { it.level == Level.INFO || it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }

    @Test
    fun `on with no user means no memory for that agent and says so`(@TempDir workspace: Path) {
        // The bucket is bound when the agent is assembled, because the userId a call carries is also the key
        // of the persisted agent state and cannot be moved just to reach memory (§7). An ownerless delivery
        // still has to get an agent — the plan asks for no memory, not a rejected request — and the whole
        // domain goes off together, so this is not the half-open row: no tools are offered against a bucket
        // that will never fill.
        var built: HarnessAgentWrapper? = null
        val lines = reporting {
            built = build(workspace, Memory(enabled = true), enableMemoryHooks = true, userId = null)
        }

        val agent = requireNotNull(built) { "an ownerless delivery still has to get an agent" }
        assertTrue(memoryTools(agent).isEmpty(), "an ownerless agent must not be offered memory: ${memoryTools(agent)}")
        val said = lines.filter { it.contains("names no user") }
        assertEquals(1, said.size, "assembly has to say why memory stayed off, got $said")
    }

    @Test
    fun `which consolidation gate the agent got is said out loud`(@TempDir workspace: Path) {
        // The plan allows two answers for the throttle gate but not a silent one: with no distributed store
        // upstream picks a per-replica gate, and a deployment that reads as "memory never consolidates" is
        // otherwise indistinguishable from a broken bucket.
        val perReplica = reporting { build(workspace, Memory(enabled = true), enableMemoryHooks = true) }
            .filter { it.contains("consolidation gate") }

        assertEquals(1, perReplica.size, "assembly must say which gate is in effect, got $perReplica")
        assertTrue(perReplica.single().contains("replica"), "the non-sandbox branch has no distributed store: ${perReplica.single()}")

        val shared = reporting {
            build(workspace, Memory(enabled = true), enableMemoryHooks = true, sandboxEnabled = true)
        }.filter { it.contains("consolidation gate") }

        assertEquals(1, shared.size, "assembly must say which gate is in effect, got $shared")
        assertTrue(shared.single().contains("store"), "the sandbox branch gets the store-backed gate: ${shared.single()}")
    }
}
