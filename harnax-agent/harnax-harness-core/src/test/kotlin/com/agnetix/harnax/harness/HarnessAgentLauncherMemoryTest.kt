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
import com.agnetix.harnax.harness.memory.BucketScopedWatermarkStore
import com.agnetix.harnax.harness.memory.LongTermMemoryContextMiddleware
import com.agnetix.harnax.harness.memory.MemoryPromoter
import com.agnetix.harnax.harness.memory.MemoryPromotionMiddleware
import com.agnetix.harnax.harness.minio.MinioBaseStore
import com.agnetix.harnax.harness.minio.ProcessLocalCoordinationStore
import com.agnetix.harnax.tools.sdk.UserIdentifier
import com.agnetix.harnax.tools.sdk.adaptor.ToolCallLogAdaptor
import io.agentscope.core.state.AgentStateStore
import io.agentscope.harness.agent.IsolationScope
import io.agentscope.harness.agent.coordination.LocalPeriodicGate
import io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate
import io.agentscope.harness.agent.filesystem.CompositeFilesystem
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware
import io.agentscope.harness.agent.middleware.MemoryMaintenanceMiddleware
import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Duration
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

    private fun spec(memoryEnabled: Boolean = true, tenantId: Long? = 4L, sessionMemoryEnabled: Boolean = false) = AgentSpec.builder()
        .id(1L)
        .tenantId(tenantId)
        .name("Research")
        .description("a research agent")
        .systemPrompt("answer")
        .chatModelId(100L)
        .memoryEnabled(memoryEnabled)
        .sessionMemoryEnabled(sessionMemoryEnabled)
        .build()

    private fun build(
        workspaceRoot: Path,
        memory: Memory,
        enableMemoryHooks: Boolean = false,
        enableWorkspaceContext: Boolean = false,
        sandboxEnabled: Boolean = false,
        minioConfig: MinioConfig? = minio(),
        userId: Long? = 1L,
        memoryEnabled: Boolean = true,
        sessionMemoryEnabled: Boolean = false,
    ): HarnessAgentWrapper = launcher(
        workspaceRoot = workspaceRoot,
        memory = memory,
        enableMemoryHooks = enableMemoryHooks,
        enableWorkspaceContext = enableWorkspaceContext,
        sandboxEnabled = sandboxEnabled,
        minioConfig = minioConfig,
    ).createSingleAgent(
        agentSpec = spec(memoryEnabled, sessionMemoryEnabled = sessionMemoryEnabled),
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
    fun `an agent that turned memory off gets no hooks no tools and no borrowed reader`(@TempDir workspace: Path) {
        // The deployment flag and the agent's own configuration are two different asks, and the wizard answers
        // the second one per agent. Leaving any one of these mounted for an agent that declined memory is the
        // half-open row again with a per-agent cause: extraction into a bucket nothing reads, a tool that only
        // ever answers empty, or AGENTS.md injected because some other agent wanted memory.
        val agent = build(workspace, Memory(enabled = true), enableMemoryHooks = true, memoryEnabled = false)

        assertTrue(memoryTools(agent).isEmpty(), "an agent that declined memory must not be offered it: ${memoryTools(agent)}")
        assertTrue(middlewares(agent).none { it is MemoryFlushMiddleware }, "an agent that declined memory must not run extraction")
        assertTrue(middlewares(agent).none { it is MemoryMaintenanceMiddleware }, "an agent that declined memory must not run consolidation")
        assertTrue(
            middlewares(agent).none { it is WorkspaceContextMiddleware },
            "the reader comes with memory, not with a deployment that has it somewhere",
        )
    }

    @Test
    fun `an agent that turned memory off does not get the store probed for a gate`(@TempDir workspace: Path) {
        // The probe claims a coordination slot, and those round trips only pay for the throttle this agent will
        // never run. The store itself still backs the sandbox filesystem, so this is about the gate alone.
        val agent = build(
            workspace,
            Memory(enabled = true),
            enableMemoryHooks = true,
            sandboxEnabled = true,
            memoryEnabled = false,
        )

        assertInstanceOf(
            MinioBaseStore::class.java,
            requireNotNull(agent.harnessAgent.distributedStore).baseStore(),
            "with memory off for this agent the raw store goes upstream: no slot is claimed and nothing is wrapped",
        )
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

    /**
     * The bucket tuple the mounted route behind [path] addresses. Which of the two layers a conversation
     * writes into is the whole content of the session-layer switch, and the tuple is the only observable that
     * says it on an agent built against a store nobody is going to contact.
     */
    private fun mountedNamespace(agent: HarnessAgentWrapper, path: String): List<String> {
        val composite = assertInstanceOf(
            CompositeFilesystem::class.java,
            agent.harnessAgent.workspaceManager.filesystem,
            "memory mounted means a composite in front of the configured filesystem",
        )
        return assertInstanceOf(
            RemoteFilesystem::class.java,
            composite.filesystemFor(path),
            "$path should answer from a memory bucket",
        ).let { (privateField(it, "namespaceFactory") as NamespaceFactory).getNamespace(null) }
    }

    @Test
    fun `an agent that did not ask for two layers keeps today's keys and today's injected blocks`(@TempDir workspace: Path) {
        // Design 11.10 clause 1, the regression anchor for the whole layer: with the switch off nothing about
        // this agent's memory moves — not the object keys, not what reaches the model, not the tools.
        val agent = build(workspace, Memory(enabled = true), enableMemoryHooks = true)

        assertEquals(listOf("tenants", "4", "users", "1", "agents", "Research", "root"), mountedNamespace(agent, "MEMORY.md"))
        assertEquals(listOf("tenants", "4", "users", "1", "agents", "Research", "memory"), mountedNamespace(agent, "memory/2026-10-05.md"))
        assertTrue(
            middlewares(agent).none { it is LongTermMemoryContextMiddleware },
            "one layer needs no second block: the reader already injects it",
        )
        assertTrue(
            middlewares(agent).none { it is MemoryPromotionMiddleware },
            "one layer writes the owner's bucket directly, so there is nothing to promote out of",
        )
    }

    @Test
    fun `an agent that asked for two layers extracts into its own conversation`(@TempDir workspace: Path) {
        // The session layer takes the two canonical routes because the flush, the consolidation and the four
        // tools all key off those prefixes; the long-term layer cannot also sit there, so it is injected by
        // its own middleware instead. The tools stay offered — they now archive into this conversation.
        val agent = build(
            workspace,
            Memory(enabled = true, consolidationMinGap = Duration.ofMinutes(11)),
            enableMemoryHooks = true,
            sessionMemoryEnabled = true,
        )

        assertEquals(
            listOf("tenants", "4", "users", "1", "agents", "Research", "sessions", "sess-1", "root"),
            mountedNamespace(agent, "MEMORY.md"),
        )
        assertEquals(
            listOf("tenants", "4", "users", "1", "agents", "Research", "sessions", "sess-1", "memory"),
            mountedNamespace(agent, "memory/2026-10-05.md"),
        )
        assertEquals(
            1,
            middlewares(agent).count { it is LongTermMemoryContextMiddleware },
            "the owner's curated layer has to reach a conversation that has none of its own yet",
        )
        assertTrue(memoryTools(agent).containsAll(listOf("memory_search", "memory_get", "memory_save")), "got ${memoryTools(agent)}")

        // A conversation layer nobody drains is a memory that quietly stops existing once the conversation is
        // gone, so the promotion path is part of what the switch buys rather than an optional extra.
        val promotion = middlewares(agent).filterIsInstance<MemoryPromotionMiddleware>()
        assertEquals(1, promotion.size, "the second half of the session layer: got $promotion")
        assertEquals(Duration.ofMinutes(11), privateField(promotion.single(), "minGap"))
        assertEquals("sess-1", privateField(promotion.single(), "sessionId"))
        assertInstanceOf(
            MemoryPromoter::class.java,
            privateField(promotion.single(), "promoter"),
        )
    }

    @Test
    fun `a store that ignores its version precondition is refused the merge`(@TempDir workspace: Path) {
        // Two conversations of one owner promote on independent clocks by design, so the only thing standing
        // between a merge and a sibling's promoted text is a version comparison the store really performs.
        // A throttle can degrade when this store cannot hold a slot; a last-write-wins merge just loses memory.
        val ignoring = object : BaseStore by InMemoryStore() {
            override fun putIfVersion(
                namespace: List<String>,
                key: String,
                value: Map<String, Any>,
                expectedVersion: Long,
            ): Boolean {
                put(namespace, key, value)
                return true
            }
        }

        assertFalse(launcher(workspace, Memory(enabled = true)).promoterIsSafe(ignoring))
    }

    @Test
    fun `a store that compares versions keeps the merge and one that could not be asked still tries it`(@TempDir workspace: Path) {
        val comparing = InMemoryStore()
        assertTrue(
            launcher(workspace, Memory(enabled = true)).promoterIsSafe(comparing),
            "a store that answered all three probe arms is what this feature is built on",
        )

        // A store that threw is not a store found wanting: the probe got no answer, and the merge meets the same
        // store on its own reads and writes, where a fault costs one merge and destroys nothing.
        val unreachable = object : BaseStore {
            override fun get(
                namespace: List<String>,
                key: String,
            ): StoreItem? = throw IllegalStateException("connection refused")

            override fun put(
                namespace: List<String>,
                key: String,
                value: Map<String, Any>,
            ): Unit = throw IllegalStateException("connection refused")

            override fun search(
                namespace: List<String>,
                limit: Int,
                offset: Int,
            ): List<StoreItem> = throw IllegalStateException("connection refused")

            override fun delete(
                namespace: List<String>,
                key: String,
            ): Unit = throw IllegalStateException("connection refused")
        }
        assertTrue(
            launcher(workspace, Memory(enabled = true)).promoterIsSafe(unreachable),
            "an unclassified store is not a refused verdict",
        )
    }

    @Test
    fun `the consolidation progress follows whichever bucket the routes point at`(@TempDir workspace: Path) {
        // A conversation that consolidates must mark its own ledgers done, not its owner's cross-session ones —
        // the same object 11.7 moved out of the shared address, now scoped per layer.
        val agent = build(
            workspace,
            Memory(enabled = true),
            enableMemoryHooks = true,
            sandboxEnabled = true,
            sessionMemoryEnabled = true,
        )

        val base = requireNotNull(agent.harnessAgent.distributedStore).baseStore()
        assertEquals(
            listOf("tenants", "4", "users", "1", "agents", "Research", "sessions", "sess-1", "memory"),
            privateField(base, "watermarkNamespace"),
            "beside the ledgers it counts, inside the conversation's own bucket",
        )
    }

    @Test
    fun `the session switch does not open memory on its own`(@TempDir workspace: Path) {
        // The first row of 11.3's matrix: long-term off means the agent has no memory domain at all, and the
        // second column does not participate. Mounting a conversation bucket here would be extraction into a
        // bucket nothing reads and no promotion path owns.
        val agent = build(
            workspace,
            Memory(enabled = true),
            enableMemoryHooks = true,
            memoryEnabled = false,
            sessionMemoryEnabled = true,
        )

        assertTrue(memoryTools(agent).isEmpty(), "got ${memoryTools(agent)}")
        assertTrue(middlewares(agent).none { it is LongTermMemoryContextMiddleware }, "no domain, no injection")
        assertTrue(middlewares(agent).none { it is MemoryFlushMiddleware }, "no domain, no extraction")
        // Which bucket a delivery mounts is decided in one place, and the composite is not the observable:
        // upstream registers its own MEMORY.md and memory/ routes for every filesystem, mounted or not.
        val domain = launcher(workspace, Memory(enabled = true), enableMemoryHooks = true)
            .memoryDomainOf(
                bucketStore(),
                spec(memoryEnabled = false, sessionMemoryEnabled = true),
                UserIdentifier(userId = 1L),
                false,
                Memory(enabled = true),
            )
        assertNull(domain, "the second switch does not open a memory domain on its own")
    }

    @Test
    fun `a store that cannot be reached is not handed the consolidation claims`(@TempDir workspace: Path) {
        // The MinIO coordinates of this harness point at nothing, so the probe gets no answer at all and the
        // honest verdict is "unusable as a gate". The bucket itself still goes to that store — a memory that
        // cannot be written has to fail loudly — but the throttle that decides who consolidates does not.
        val agent = build(workspace, Memory(enabled = true), enableMemoryHooks = true, sandboxEnabled = true)

        val store = agent.harnessAgent.distributedStore
        assertNotNull(store, "the sandbox branch is the one that gets a distributed store")
        assertInstanceOf(
            ProcessLocalCoordinationStore::class.java,
            privateField(requireNotNull(store).baseStore(), "delegate"),
            "upstream builds the gate from this store alone, so a store that never answered has to be " +
                "answered for: with no distributed store at all it would fall back to LocalPeriodicGate. " +
                "The bucket wrapper that relocates the consolidation progress sits in front of that answer.",
        )
    }

    @Test
    fun `the consolidation progress of one agent is keyed to that agent's own bucket`(@TempDir workspace: Path) {
        // How far an owner's daily ledgers have been merged is one object upstream keeps at an address with no
        // tenant, user, agent or session in it. Left there, one owner's pass decides which of another owner's
        // ledgers count as already handled — and the entries it skips go silently. The relocation has to be on
        // the store handed to the distributed builder because that is the only store upstream reads.
        val agent = build(workspace, Memory(enabled = true), enableMemoryHooks = true, sandboxEnabled = true)

        val base = requireNotNull(agent.harnessAgent.distributedStore).baseStore()
        assertInstanceOf(
            BucketScopedWatermarkStore::class.java,
            base,
            "a bucket that consolidates advances its own progress and nobody else's",
        )
        assertEquals(
            listOf("tenants", "4", "users", "1", "agents", "Research", "memory"),
            privateField(base, "watermarkNamespace"),
            "beside the ledgers it counts, so the two whole-bucket reclamation paths take it along",
        )
    }

    @Test
    fun `an ownerless delivery gets no progress relocation because it has no bucket`(@TempDir workspace: Path) {
        // Memory was asked for and this delivery could not bind an owner, so no routes are mounted and nothing
        // is written to a bucket. The gate fallback still applies — the hooks' throttle is a separate question —
        // but relocating the progress would need a bucket to relocate it into.
        val agent = build(
            workspace,
            Memory(enabled = true),
            enableMemoryHooks = true,
            sandboxEnabled = true,
            userId = null,
        )

        val base = requireNotNull(agent.harnessAgent.distributedStore).baseStore()
        assertInstanceOf(
            ProcessLocalCoordinationStore::class.java,
            base,
            "the gate answer stays, and nothing wraps it",
        )
    }

    @Test
    fun `a tenant-scoped bucket on an agent that carries no tenant is refused`(@TempDir workspace: Path) {
        val failure = assertThrows(IllegalStateException::class.java) {
            launcher(workspace, Memory(enabled = true), enableMemoryHooks = true)
                .memoryDomainOf(bucketStore(), spec(tenantId = null), UserIdentifier(userId = 1L), false, Memory(enabled = true))
        }

        assertTrue(failure.message!!.contains("carries no tenant"), failure.message!!)
    }

    @Test
    fun `with the tenant segment off the whole bucket keys on the owner alone`(@TempDir workspace: Path) {
        // One key root for both layers and the progress between them, so a deployment that switches the tenant
        // off does not leave the consolidation progress keyed by a tenant the routes no longer read.
        val memory = Memory(enabled = true, tenantScoped = false)

        val domain = launcher(workspace, memory, enableMemoryHooks = true)
            .memoryDomainOf(bucketStore(), spec(tenantId = null), UserIdentifier(userId = 1L), false, memory)

        assertEquals(
            listOf("users", "1", "agents", "Research"),
            requireNotNull(domain).namespace(null),
            "an unscoped agent still gets a bucket of its own, and no `tenants/null` segment",
        )
    }

    /** A store that is never contacted: resolving a domain only binds the bucket to it. */
    private fun bucketStore() = MinioBaseStore(minio().createMinioClient(), "harnax-store", "store/")

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
        // The two hooks go with the domain, not with the operator's separate switch: left installed they keep
        // extracting into the framework's own MEMORY.md — the host directory, or whatever isolation scope the
        // sandbox was configured with — while the warn above says this agent got no memory at all.
        assertTrue(middlewares(agent).none { it is MemoryFlushMiddleware }, "an ownerless agent must not run extraction")
        assertTrue(
            middlewares(agent).none { it is MemoryMaintenanceMiddleware },
            "an ownerless agent must not run consolidation",
        )
        val said = lines.filter { it.contains("names no user") }
        assertEquals(1, said.size, "assembly has to say why memory stayed off, got $said")
    }

    /** The two hooks this domain installs, whichever order the middleware list happens to be in. */
    private fun memoryHooks(agent: HarnessAgentWrapper) = middlewares(agent).filter { it is MemoryFlushMiddleware || it is MemoryMaintenanceMiddleware }

    /**
     * Upstream keeps both fields private and picks them itself, so the honest answer to "which gate did this
     * agent get" is the field the hook will consult at flush time — not the line logged about it.
     */
    private fun privateField(target: Any, name: String): Any = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    @Test
    fun `the gate the log names is the gate the hooks got`(@TempDir workspace: Path) {
        // The plan allows two answers for the throttle gate but not a silent one, because a deployment that
        // reads as "memory never consolidates" is otherwise indistinguishable from a broken bucket. The
        // message is only worth having once the fields it describes are checked as well: upstream chooses the
        // gate from the distributed store alone, and the two hooks share it under two separate slot keys.
        var local: HarnessAgentWrapper? = null
        val perReplica = reporting { local = build(workspace, Memory(enabled = true), enableMemoryHooks = true) }
            .filter { it.contains("consolidation gate") }
        val localHooks = memoryHooks(requireNotNull(local))

        assertEquals(1, perReplica.size, "assembly must say which gate is in effect, got $perReplica")
        assertEquals(2, localHooks.size, "both hooks were asked for, got $localHooks")
        localHooks.forEach {
            assertInstanceOf(
                LocalPeriodicGate::class.java,
                privateField(it, "periodicGate"),
                "with no distributed store upstream falls back to the per-replica gate: ${perReplica.single()}",
            )
            assertEquals(IsolationScope.SESSION, privateField(it, "isolationScope"))
        }
        assertSame(
            privateField(localHooks.first(), "periodicGate"),
            privateField(localHooks.last(), "periodicGate"),
            "one gate for both hooks, so the message may name a single gate",
        )

        var shared: HarnessAgentWrapper? = null
        val sharedLines = reporting {
            shared = build(workspace, Memory(enabled = true), enableMemoryHooks = true, sandboxEnabled = true)
        }.filter { it.contains("consolidation gate") }
        val sharedHooks = memoryHooks(requireNotNull(shared))

        assertEquals(1, sharedLines.size, "assembly must say which gate is in effect, got $sharedLines")
        assertEquals(2, sharedHooks.size, "both hooks were asked for, got $sharedHooks")
        sharedHooks.forEach {
            assertInstanceOf(
                StoreBackedPeriodicGate::class.java,
                privateField(it, "periodicGate"),
                "the sandbox branch has a distributed store, so upstream picks the store-backed class; whether " +
                    "a claim is really shared is the store's to answer, which the row above covers: ${sharedLines.single()}",
            )
            assertEquals(IsolationScope.SESSION, privateField(it, "isolationScope"))
        }

        // Flush and maintenance are two slots of one gate, each prefixed with its own name: an operator who
        // goes looking for the throttle has to be told both keys, and "one slot" would send them to one.
        for (line in listOf(perReplica.single(), sharedLines.single())) {
            assertTrue(line.contains("memory-flush"), "the flush slot key has to be named, got: $line")
            assertTrue(line.contains("memory-maintenance"), "the consolidation slot key has to be named, got: $line")
            assertTrue(line.contains("SESSION"), "the scope in the key has to be named, got: $line")
        }
        assertTrue(perReplica.single().contains("this replica alone"), "the non-sandbox branch has no distributed store: ${perReplica.single()}")
        assertTrue(
            sharedLines.single().contains("the store cannot hold a slot"),
            "this harness points at an unreachable store, so the log must not promise cross-replica deduplication: " +
                sharedLines.single(),
        )
    }
}
