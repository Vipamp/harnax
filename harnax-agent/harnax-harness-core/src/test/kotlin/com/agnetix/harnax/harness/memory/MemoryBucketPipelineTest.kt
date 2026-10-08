package com.agnetix.harnax.harness.memory

import com.agnetix.harnax.agent.AgentSpec
import com.agnetix.harnax.agent.ChatSpec
import com.agnetix.harnax.agent.adaptor.ChatModelConfigAdaptor
import com.agnetix.harnax.agent.adaptor.McpConfigAdaptor
import com.agnetix.harnax.agent.adaptor.PlanNoteAdaptor
import com.agnetix.harnax.agent.adaptor.ProcessLogAdaptor
import com.agnetix.harnax.agent.adaptor.SkillAdaptor
import com.agnetix.harnax.agent.adaptor.TokenStatAdaptor
import com.agnetix.harnax.agent.adaptor.model.OpenAIChatModelConfig
import com.agnetix.harnax.harness.HarnessAgentLauncher
import com.agnetix.harnax.harness.HarnessAgentWrapper
import com.agnetix.harnax.harness.config.HarnessConfig
import com.agnetix.harnax.harness.config.Memory
import com.agnetix.harnax.harness.config.MinioConfig
import com.agnetix.harnax.harness.config.SandboxConfig
import com.agnetix.harnax.tools.sdk.UserIdentifier
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.message.ContentBlock
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.model.ChatResponse
import io.agentscope.core.model.Model
import io.agentscope.core.state.AgentState
import io.agentscope.core.state.AgentStateStore
import io.agentscope.harness.agent.memory.MemoryConsolidator
import io.agentscope.harness.agent.memory.MemoryFlushManager
import io.agentscope.harness.agent.middleware.WorkspaceContextMiddleware
import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The memory addresses measured end to end on the real store: a conversation's extraction lands in that
 * conversation's own bucket, a consolidation pass curates it into that bucket's `MEMORY.md`, and the
 * owner's long-term layer — which nothing here writes, because reaching it takes an approved merge — still
 * reaches a brand-new conversation of the same owner.
 *
 * This is the seam where the mount could quietly fail: the flush path appends through
 * `WorkspaceManager`, the read path falls back to the host disk when a route answers nothing, and the
 * store is shared while the workspace directory is not. Only a real MinIO behind a real built agent can
 * tell "the memory moved to the bucket these routes point at" from "it stayed on this replica and looked
 * fine".
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryBucketPipelineTest {

    companion object {
        private const val BUCKET = "harnax-store"
        private const val PREFIX = "store/"
        private const val ACCESS_KEY = "minioadmin"
        private const val SECRET_KEY = "minioadmin"
        private const val EXTRACTED = "- the user wants replies in Chinese"
        private const val AGENT_NAME = "Research"
        private const val CLEARING_AGENT = "ResearchClearing"

        @Container
        @JvmStatic
        val minio: GenericContainer<*> = GenericContainer(DockerImageName.parse("minio/minio:latest"))
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
    }

    // The owner is the `UserIdentifier` this agent was assembled for, not anything the call carries.
    private fun memoryNamespace(agentName: String = AGENT_NAME) = listOf("tenants", "4", "users", "1", "agents", agentName, "memory")

    private fun rootNamespace(agentName: String = AGENT_NAME) = listOf("tenants", "4", "users", "1", "agents", agentName, "root")

    /** The two addresses one conversation's mounted routes answer from. */
    private fun conversationLedgers(
        sessionId: String,
        agentName: String = AGENT_NAME,
    ) = listOf("tenants", "4", "users", "1", "agents", agentName, "sessions", sessionId, "memory")

    private fun conversationDraft(
        sessionId: String,
        agentName: String = AGENT_NAME,
    ) = listOf("tenants", "4", "users", "1", "agents", agentName, "sessions", sessionId, "root")

    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun store() = com.agnetix.harnax.harness.minio.MinioBaseStore(minioClient(), BUCKET, PREFIX)

    private fun minioClient(): MinioClient = MinioClient.builder()
        .endpoint("http://${minio.host}:${minio.getMappedPort(9000)}")
        .credentials(ACCESS_KEY, SECRET_KEY)
        .build()

    @BeforeAll
    fun createBucket() {
        val client = minioClient()
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build())
        }
    }

    /** Every model call answers with one fixed string: the extraction and curation quality is upstream's problem. */
    private class ScriptedModel(private val answer: String) : Model {
        override fun stream(
            messages: List<Msg>,
            tools: List<io.agentscope.core.model.ToolSchema>?,
            options: io.agentscope.core.model.GenerateOptions?,
        ): reactor.core.publisher.Flux<ChatResponse> = reactor.core.publisher.Flux.just(
            ChatResponse.builder().content(listOf<ContentBlock>(TextBlock.builder().text(answer).build())).build(),
        )

        override fun getModelName(): String = "scripted"
    }

    /** The launcher that built the agent under test; session-scoped operations live on it. */
    private lateinit var builtBy: HarnessAgentLauncher

    private fun agent(
        workspace: Path,
        agentName: String = AGENT_NAME,
    ): HarnessAgentWrapper {
        val launcher = HarnessAgentLauncher(
            chatModelConfigAdaptor = ChatModelConfigAdaptor { OpenAIChatModelConfig(modelName = "gpt-test", apiKey = "k") },
            mcpConfigAdaptor = McpConfigAdaptor { null },
            stateStore = mock(AgentStateStore::class.java),
            skillAdaptor = mock(SkillAdaptor::class.java),
            tokenStatAdaptor = mock(TokenStatAdaptor::class.java),
            processLogAdaptor = mock(ProcessLogAdaptor::class.java),
            planNoteAdaptor = mock(PlanNoteAdaptor::class.java),
            workspaceRoot = workspace,
            harnessConfig = HarnessConfig(
                sandbox = SandboxConfig(enabled = false),
                enableMemoryHooks = true,
                memory = Memory(enabled = true, flushTrigger = "always"),
            ),
            minioConfig = MinioConfig(
                endpoint = "http://${minio.host}:${minio.getMappedPort(9000)}",
                accessKey = ACCESS_KEY,
                secretKey = SECRET_KEY,
                storeBucket = BUCKET,
                storePrefix = PREFIX,
            ),
        )
        builtBy = launcher
        return launcher.createSingleAgent(
            agentSpec = AgentSpec.builder()
                .id(1L)
                .tenantId(4L)
                .name(agentName)
                .description("a research agent")
                .systemPrompt("answer")
                .chatModelId(100L)
                .build(),
            sessionId = "sess-A",
            chatSpec = ChatSpec.builder().build(),
            userIdentifier = UserIdentifier(userId = 1L),
        )
    }

    /**
     * What production really hands the filesystem: no user. [HarnessAgentWrapper] keeps its `userId` unset
     * because that value is also the key of the persisted agent state, so the bucket can only be right if
     * the owner was captured when the agent was assembled.
     */
    private fun rc(sessionId: String) = RuntimeContext.builder().sessionId(sessionId).build()

    private fun conversation() = listOf(
        Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.builder().text("以后都用中文回复我").build())
            .build(),
    )

    @Test
    fun `one conversation's memory stays in its own bucket while a new conversation still reads its owner's layer`(@TempDir workspace: Path) {
        val built = agent(workspace)
        val manager = built.harnessAgent.workspaceManager

        MemoryFlushManager(manager, ScriptedModel(EXTRACTED), MemoryFlushManager.DEFAULT_FLUSH_PROMPT)
            .flushMemories(rc("sess-A"), conversation())
            .block(Duration.ofSeconds(30))

        val ledgers = store().search(conversationLedgers("sess-A"), 10, 0)
        assertTrue(
            ledgers.any { it.value()["content"]?.toString()?.contains(EXTRACTED) == true },
            "the flush must land in this conversation's own bucket, found ${ledgers.map { it.key() }}",
        )
        assertNotNull(
            store().get(conversationLedgers("sess-A"), "/$today.md"),
            "the daily ledger is keyed by owner, agent and conversation",
        )
        assertTrue(
            store().search(memoryNamespace(), 10, 0).isEmpty(),
            "one turn of one conversation cannot reach the ledgers every other conversation of this owner reads",
        )

        MemoryConsolidator(manager, ScriptedModel("$EXTRACTED\n"), 4_000)
            .consolidate(rc("sess-A"))
            .block(Duration.ofSeconds(30))

        val curated = requireNotNull(store().get(conversationDraft("sess-A"), "/MEMORY.md")) {
            "consolidation must curate the ledger into this conversation's MEMORY.md"
        }
        assertTrue(
            curated.value()["content"]?.toString()?.contains(EXTRACTED) == true,
            "got ${curated.value()["content"]}",
        )
        assertNull(
            store().get(rootNamespace(), "/MEMORY.md"),
            "the long-term layer is a merge's output, not a turn's",
        )

        // What an approved merge leaves behind, read back on the very first turn of a conversation that has
        // nothing of its own yet: the long-term layer goes in as its own block because the mounted `MEMORY.md`
        // route answers from this conversation's bucket.
        val domain = MemoryDomain(store(), 4L, "1", AGENT_NAME, true)
        domain.routes()
            .getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
            .write(rc("sess-C"), MemoryFilesystemRoutes.CURATED_ITEM_KEY, "$EXTRACTED\n")

        val injected = requireNotNull(
            LongTermMemoryContextMiddleware(domain)
                .onSystemPrompt(built.harnessAgent, rc("sess-C"), "you are an agent")
                .block(Duration.ofSeconds(30)),
        ) { "the long-term reader must answer with the injected prompt" }

        assertTrue(injected.contains(EXTRACTED), "a new conversation starts with its owner's curated memory, got: $injected")
    }

    @Test
    fun `the memory pipeline leaves no copy on the host disk`(@TempDir workspace: Path) {
        // A route that answers nothing makes `readMemoryMd` fall back to the host file unconditionally
        // (`WorkspaceManager.java:819-823`). So the mount only holds while nothing ever writes that file —
        // which is what this measures, on both the flush and the consolidation leg.
        val built = agent(workspace)
        val manager = built.harnessAgent.workspaceManager

        MemoryFlushManager(manager, ScriptedModel(EXTRACTED), MemoryFlushManager.DEFAULT_FLUSH_PROMPT)
            .flushMemories(rc("sess-A"), conversation())
            .block(Duration.ofSeconds(30))
        MemoryConsolidator(manager, ScriptedModel("$EXTRACTED\n"), 4_000)
            .consolidate(rc("sess-A"))
            .block(Duration.ofSeconds(30))

        val agentDir = workspace.resolve(AGENT_NAME).resolve("sess-A")
        assertTrue(
            java.nio.file.Files.notExists(agentDir.resolve("MEMORY.md")),
            "a MEMORY.md on the host disk answers every anonymous read: ${listHostMarkdown(agentDir)}",
        )
        assertTrue(
            java.nio.file.Files.notExists(agentDir.resolve("memory").resolve("$today.md")),
            "and the daily ledger must not sit there either: ${listHostMarkdown(agentDir)}",
        )
    }

    @Test
    fun `the ledger glob answers from the mounted bucket`(@TempDir workspace: Path) {
        // Maintenance expires ledgers by listing them (`MemoryMaintenanceMiddleware.expireDailyFiles`
        // globs `*.md` under `memory`), so if that listing kept answering from the session workspace the
        // bucket would grow one file per day forever and never archive. This is plan §9.1 measured.
        val built = agent(workspace)
        val manager = built.harnessAgent.workspaceManager
        MemoryFlushManager(manager, ScriptedModel(EXTRACTED), MemoryFlushManager.DEFAULT_FLUSH_PROMPT)
            .flushMemories(rc("sess-A"), conversation())
            .block(Duration.ofSeconds(30))

        val listed = manager.filesystem.glob(rc("sess-A"), "*.md", "memory")
        val paths = listed.matches().orEmpty().map { it.path() }
        assertTrue(
            paths.any { it.contains(today) },
            "the mounted bucket has to be what the listing sees, got $paths",
        )

        val named = manager.filesystem.glob(
            RuntimeContext.builder().userId("someone-else").sessionId("sess-A").build(),
            "*.md",
            "memory",
        )
        assertEquals(
            paths,
            named.matches().orEmpty().map { it.path() },
            "the call cannot redirect the listing into the user it names: ${named.matches().orEmpty().map { it.path() }}",
        )
    }

    @Test
    fun `the injected memory never becomes a chat message`(@TempDir workspace: Path) {
        // Chat history is built from the conversation state alone (`DefaultAgentRunner.loadHistory` reads
        // `HarnessAgentLauncher.loadSessionMessages`, which reads `AgentState.context`), so what keeps
        // memory out of the bubbles is that injection stays on the system-prompt channel.
        val built = agent(workspace)
        val manager = built.harnessAgent.workspaceManager
        MemoryFlushManager(manager, ScriptedModel(EXTRACTED), MemoryFlushManager.DEFAULT_FLUSH_PROMPT)
            .flushMemories(rc("sess-A"), conversation())
            .block(Duration.ofSeconds(30))
        MemoryConsolidator(manager, ScriptedModel("$EXTRACTED\n"), 4_000)
            .consolidate(rc("sess-A"))
            .block(Duration.ofSeconds(30))

        val state = AgentState.builder().addMessage(conversation().first()).build()
        val context = RuntimeContext.builder().sessionId("sess-B").agentState(state).build()
        val injected = requireNotNull(
            WorkspaceContextMiddleware(manager).onSystemPrompt(built.harnessAgent, context, "you are an agent")
                .block(Duration.ofSeconds(30)),
        ) { "the workspace-context middleware must answer with the injected prompt" }

        assertTrue(injected.contains(EXTRACTED), "the injection is what this test is about, got: $injected")
        assertEquals(1, state.context.size, "memory must not arrive as a message: ${state.context.map { it.textContent }}")
        assertTrue(
            state.context.none { it.textContent.contains(EXTRACTED) },
            "and must not be folded into one: ${state.context.map { it.textContent }}",
        )
    }

    @Test
    fun `clearing the session leaves the memory buckets alone`(@TempDir workspace: Path) {
        // Its own agent name: the container is shared for the whole class, and the object seeded below is the
        // one another test asserts stays empty for its own agent.
        val built = agent(workspace, CLEARING_AGENT)
        val manager = built.harnessAgent.workspaceManager
        MemoryFlushManager(manager, ScriptedModel(EXTRACTED), MemoryFlushManager.DEFAULT_FLUSH_PROMPT)
            .flushMemories(rc("sess-A"), conversation())
            .block(Duration.ofSeconds(30))
        MemoryConsolidator(manager, ScriptedModel("$EXTRACTED\n"), 4_000)
            .consolidate(rc("sess-A"))
            .block(Duration.ofSeconds(30))

        // What a merge would have left behind, so the claim below is about a real object rather than an
        // address that was never written.
        val domain = MemoryDomain(store(), 4L, "1", CLEARING_AGENT, true)
        domain.routes()
            .getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
            .write(rc("sess-A"), MemoryFilesystemRoutes.CURATED_ITEM_KEY, "$EXTRACTED\n")

        builtBy.clearSession("sess-A")

        assertNotNull(
            store().get(conversationLedgers("sess-A", CLEARING_AGENT), "/$today.md"),
            "clearing one conversation must not cost the owner everything they told this agent",
        )
        assertNotNull(
            store().get(conversationDraft("sess-A", CLEARING_AGENT), "/MEMORY.md"),
            "the conversation's own curated draft is memory too, not conversation state",
        )
        assertNotNull(
            store().get(rootNamespace(CLEARING_AGENT), "/MEMORY.md"),
            "and neither path reclaims the long-term layer",
        )
    }

    private fun listHostMarkdown(dir: Path): List<String> = if (!java.nio.file.Files.exists(dir)) {
        emptyList()
    } else {
        java.nio.file.Files.walk(dir)
            .filter { java.nio.file.Files.isRegularFile(it) }
            .map { dir.relativize(it).toString() }
            .toList()
    }
}
