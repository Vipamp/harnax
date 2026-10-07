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
import com.agnetix.harnax.harness.minio.MinioBaseStore
import com.agnetix.harnax.tools.sdk.UserIdentifier
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEndEvent
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.message.ContentBlock
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.middleware.AgentInput
import io.agentscope.core.model.ChatResponse
import io.agentscope.core.model.GenerateOptions
import io.agentscope.core.model.Model
import io.agentscope.core.model.ToolSchema
import io.agentscope.core.state.AgentState
import io.agentscope.core.state.AgentStateStore
import io.agentscope.harness.agent.IsolationScope
import io.agentscope.harness.agent.coordination.PeriodicGate
import io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks
import io.agentscope.harness.agent.memory.MemoryConfig
import io.agentscope.harness.agent.memory.MemoryConsolidator
import io.agentscope.harness.agent.memory.MemoryFlushManager
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware
import io.agentscope.harness.agent.middleware.MemoryFlushMiddleware
import io.agentscope.harness.agent.middleware.MemoryMaintenanceMiddleware
import io.agentscope.harness.agent.workspace.WorkspaceManager
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
import reactor.core.publisher.Flux
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

/**
 * What the background pipeline does when the two things it depends on fail: the store cannot
 * compare-and-swap, and the extraction model throws.
 *
 * Both legs are measured against a live MinIO behind an agent the launcher actually built, because the
 * claim under test is about the mount as much as about the gate — a throttled flush that writes nothing
 * is indistinguishable from a flush that writes to the wrong bucket. Each negative leg carries a positive
 * twin in the same fixture, so a broken harness cannot make the negatives look green.
 *
 * The negative CAS legs stand in for a deployment whose MinIO lacks conditional writes (§9.2 of the
 * plan): only the {@code putIfVersion} answer is faked, every other call reaches the real server.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryGateFalsificationTest {

    companion object {
        private const val BUCKET = "harnax-store"
        private const val PREFIX = "store/"
        private const val ACCESS_KEY = "minioadmin"
        private const val SECRET_KEY = "minioadmin"
        private const val AGENT_ID = "Research"
        private const val EXTRACTED = "- the user wants replies in Chinese"

        @Container
        @JvmStatic
        val minio: GenericContainer<*> = GenericContainer(DockerImageName.parse("minio/minio:latest"))
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
    }

    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    private fun minioClient(): MinioClient = MinioClient.builder()
        .endpoint("http://${minio.host}:${minio.getMappedPort(9000)}")
        .credentials(ACCESS_KEY, SECRET_KEY)
        .build()

    private fun store(): MinioBaseStore = MinioBaseStore(minioClient(), BUCKET, PREFIX)

    private fun ownerNs(owner: String, segment: String) = listOf("tenants", "4", "users", owner, "agents", AGENT_ID, segment)

    private fun ledgerContent(owner: String): String? = store().get(ownerNs(owner, "memory"), "/$today.md")?.value()?.get("content")?.toString()

    private fun memoryMdContent(owner: String): String? = store().get(ownerNs(owner, "root"), "/MEMORY.md")?.value()?.get("content")?.toString()

    @BeforeAll
    fun createBucket() {
        val client = minioClient()
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build())
        }
    }

    /**
     * A store on a MinIO that will not answer a conditional write: reads, plain writes and searches are the
     * real thing, only the CAS outcome is refused. This is what an older or non-S3-compatible deployment
     * looks like from upstream's side.
     */
    private class LosingCasStore(private val delegate: MinioBaseStore) : BaseStore {
        override fun get(namespace: List<String>, key: String): StoreItem? = delegate.get(namespace, key)

        override fun put(namespace: List<String>, key: String, value: Map<String, Any>) = delegate.put(namespace, key, value)

        override fun putIfVersion(
            namespace: List<String>,
            key: String,
            value: Map<String, Any>,
            expectedVersion: Long,
        ): Boolean = false

        override fun search(namespace: List<String>, limit: Int, offset: Int): List<StoreItem> = delegate.search(namespace, limit, offset)

        override fun delete(namespace: List<String>, key: String) = delegate.delete(namespace, key)
    }

    /** Counts the calls it is asked for, optionally failing like a model endpoint with an outage would. */
    private class CountingModel(
        private val answer: String,
        private val throws: Boolean = false,
    ) : Model {
        val calls = AtomicInteger()

        override fun stream(
            messages: List<Msg>,
            tools: List<ToolSchema>?,
            options: GenerateOptions?,
        ): Flux<ChatResponse> {
            calls.incrementAndGet()
            if (throws) {
                return Flux.error(IllegalStateException("the extraction endpoint is down"))
            }
            return Flux.just(
                ChatResponse.builder().content(listOf<ContentBlock>(TextBlock.builder().text(answer).build())).build(),
            )
        }

        override fun getModelName(): String = "counting"
    }

    private fun agent(
        workspace: Path,
        owner: Long,
        sessionId: String = "sess-A",
    ): HarnessAgentWrapper = HarnessAgentLauncher(
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
            memory = Memory(enabled = true),
        ),
        minioConfig = MinioConfig(
            endpoint = "http://${minio.host}:${minio.getMappedPort(9000)}",
            accessKey = ACCESS_KEY,
            secretKey = SECRET_KEY,
            storeBucket = BUCKET,
            storePrefix = PREFIX,
        ),
    ).createSingleAgent(
        agentSpec = AgentSpec.builder()
            .id(1L)
            .tenantId(4L)
            .name(AGENT_ID)
            .description("a research agent")
            .systemPrompt("answer")
            .chatModelId(100L)
            .build(),
        sessionId = sessionId,
        chatSpec = ChatSpec.builder().build(),
        userIdentifier = UserIdentifier(userId = owner),
    )

    /**
     * Drives one turn through a middleware the way the agent runtime does: the conversation state arrives
     * on the context, the answer stream is whatever the next stage returns, and the memory work is
     * dispatched after that stream completes. Harnax runs every tier on
     * {@code IsolationScope.SESSION} (the filesystem spec's scope is what the memory middlewares get), so
     * that is the scope used here rather than the library default.
     *
     * No user on the context, exactly as production runs it: whatever lands in a bucket got there because
     * the owner was bound when the agent was assembled.
     */
    private fun turn(
        middleware: HarnessRuntimeMiddleware,
        built: HarnessAgentWrapper,
        session: String,
    ): List<AgentEvent> {
        val message = Msg.builder()
            .role(MsgRole.USER)
            .content(TextBlock.builder().text("以后都用中文回复我").build())
            .build()
        val rc = RuntimeContext.builder()
            .sessionId(session)
            .agentState(AgentState.builder().addMessage(message).build())
            .build()
        val answer = AgentEndEvent("reply-1")
        val next = Function<AgentInput, Flux<AgentEvent>> { Flux.just(answer) }
        return requireNotNull(middleware.onAgent(built.harnessAgent, rc, AgentInput(listOf(message)), next).collectList().block(Duration.ofSeconds(10))) {
            "the answer stream must complete on its own terms"
        }.also {
            assertEquals(listOf(answer), it, "the memory stage must not touch the answer stream")
            assertTrue(
                MemoryBackgroundTasks.awaitQuiescence(60, TimeUnit.SECONDS),
                "the background memory work never settled — this is the probe §9.3 asks about",
            )
        }
    }

    private fun flushMiddleware(
        manager: WorkspaceManager,
        model: Model,
        trigger: MemoryConfig.FlushTrigger,
        gate: PeriodicGate,
    ): MemoryFlushMiddleware = MemoryFlushMiddleware(
        manager,
        model,
        MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
        trigger,
        IsolationScope.SESSION,
        gate,
    )

    private fun maintenanceMiddleware(
        manager: WorkspaceManager,
        model: Model,
        gate: PeriodicGate,
    ): MemoryMaintenanceMiddleware = MemoryMaintenanceMiddleware(
        manager,
        MemoryConsolidator(manager, model, 4_000),
        90,
        180,
        Duration.ofMinutes(30),
        IsolationScope.SESSION,
        gate,
    )

    @Test
    fun `a store that cannot compare-and-swap throttles the flush out`(@TempDir workspace: Path) {
        val owner = "11"
        val built = agent(workspace, owner.toLong())
        val model = CountingModel(EXTRACTED)
        val gate = StoreBackedPeriodicGate(LosingCasStore(store()))

        turn(flushMiddleware(built.harnessAgent.workspaceManager, model, MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(5)), gate), built, "s-losing")

        assertEquals(0, model.calls.get(), "a lost claim must cost no extraction call")
        assertNull(ledgerContent(owner), "the throttled flush wrote nothing to the bucket")
    }

    @Test
    fun `the always trigger writes without asking the gate`(@TempDir workspace: Path) {
        // Positive twin of the leg above: same agent, same store, same gate that cannot claim. Only the
        // trigger differs, so the negative result there is the gate and not a fixture that writes nothing.
        val owner = "12"
        val built = agent(workspace, owner.toLong())
        val model = CountingModel(EXTRACTED)
        val gate = StoreBackedPeriodicGate(LosingCasStore(store()))

        turn(flushMiddleware(built.harnessAgent.workspaceManager, model, MemoryConfig.FlushTrigger.always(), gate), built, "s-always")

        assertEquals(1, model.calls.get(), "always() never consults the gate")
        val content = requireNotNull(ledgerContent(owner)) { "the ledger must land in the owner bucket" }
        assertTrue(content.contains(EXTRACTED), "got: $content")
    }

    @Test
    fun `a store that cannot compare-and-swap throttles maintenance out`(@TempDir workspace: Path) {
        val owner = "13"
        val built = agent(workspace, owner.toLong())
        val manager = built.harnessAgent.workspaceManager
        val flushModel = CountingModel(EXTRACTED)
        turn(flushMiddleware(manager, flushModel, MemoryConfig.FlushTrigger.always(), StoreBackedPeriodicGate(LosingCasStore(store()))), built, "s-maint-losing")
        assertNotNull(ledgerContent(owner), "the ledger has to be there for maintenance to have a job")

        val consolidatorModel = CountingModel("$EXTRACTED\n")
        turn(maintenanceMiddleware(manager, consolidatorModel, StoreBackedPeriodicGate(LosingCasStore(store()))), built, "s-maint-losing")

        assertEquals(0, consolidatorModel.calls.get(), "a maintenance pass that cannot claim the slot must not spend a model call")
        assertNull(memoryMdContent(owner), "and it must not write the curated file either")
    }

    @Test
    fun `a store that can compare-and-swap lets maintenance curate the ledger`(@TempDir workspace: Path) {
        // Positive twin of the leg above, on the real conditional write.
        val owner = "14"
        val built = agent(workspace, owner.toLong())
        val manager = built.harnessAgent.workspaceManager
        turn(
            flushMiddleware(manager, CountingModel(EXTRACTED), MemoryConfig.FlushTrigger.always(), StoreBackedPeriodicGate(store())),
            built,
            "s-maint-win",
        )

        val consolidatorModel = CountingModel("$EXTRACTED\n")
        turn(maintenanceMiddleware(manager, consolidatorModel, StoreBackedPeriodicGate(store())), built, "s-maint-win")

        assertEquals(1, consolidatorModel.calls.get(), "the first pass wins the claim and consolidates")
        val curated = requireNotNull(memoryMdContent(owner)) { "maintenance must curate MEMORY.md into the bucket" }
        assertTrue(curated.contains(EXTRACTED), "got: $curated")
    }

    @Test
    fun `one session pays one extraction per window`(@TempDir workspace: Path) {
        val owner = "15"
        val built = agent(workspace, owner.toLong())
        val manager = built.harnessAgent.workspaceManager
        val model = CountingModel(EXTRACTED)
        val gate = StoreBackedPeriodicGate(store())
        val trigger = MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(5))

        turn(flushMiddleware(manager, model, trigger, gate), built, "s-cap")
        turn(flushMiddleware(manager, model, trigger, gate), built, "s-cap")

        assertEquals(1, model.calls.get(), "the second turn of the same session is inside the window: ${model.calls.get()} calls")
        assertNotNull(ledgerContent(owner), "and the one flush that ran did write")
    }

    @Test
    fun `the window belongs to the session, not to the owner bucket`(@TempDir workspace: Path) {
        // What harnax's SESSION isolation scope costs: the plan bounds extraction per bucket, the gate key
        // is `memory-flush:SESSION:<sessionId>`, so a new session always starts a fresh window.
        val owner = "16"
        val built = agent(workspace, owner.toLong())
        val manager = built.harnessAgent.workspaceManager
        val model = CountingModel(EXTRACTED)
        val gate = StoreBackedPeriodicGate(store())
        val trigger = MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(5))

        turn(flushMiddleware(manager, model, trigger, gate), built, "s-window-a")
        turn(flushMiddleware(manager, model, trigger, gate), built, "s-window-b")

        assertEquals(2, model.calls.get(), "one extraction per session inside the same window")
    }

    @Test
    fun `the flush slot names the session alone, so one owner can silence another`(@TempDir workspace: Path) {
        // Found while measuring the window above: the slot key is `memory-flush:SESSION:<sessionId>` with
        // no owner in it, so uniqueness per owner rests on the session id, not on the memory bucket. Two
        // owners means two agents here, since one agent is assembled for exactly one of them; the host
        // session dirs differ while the conversation id they both run under is the same.
        val first = agent(workspace, 17L, sessionId = "host-first")
        val second = agent(workspace, 18L, sessionId = "host-second")
        val model = CountingModel(EXTRACTED)
        val gate = StoreBackedPeriodicGate(store())
        val trigger = MemoryConfig.FlushTrigger.throttled(Duration.ofMinutes(5))

        turn(flushMiddleware(first.harnessAgent.workspaceManager, model, trigger, gate), first, "shared-slot")
        turn(flushMiddleware(second.harnessAgent.workspaceManager, model, trigger, gate), second, "shared-slot")

        assertEquals(1, model.calls.get(), "the second owner's turn lost the claim the first one held")
        assertNotNull(ledgerContent("17"), "the owner that claimed wrote its own bucket")
        assertNull(ledgerContent("18"), "and the throttled owner wrote nothing of its own")
    }

    @Test
    fun `an extraction that throws costs the answer nothing and changes no bytes`(@TempDir workspace: Path) {
        val owner = "19"
        val built = agent(workspace, owner.toLong())
        val manager = built.harnessAgent.workspaceManager
        val workingModel = CountingModel(EXTRACTED)
        turn(flushMiddleware(manager, workingModel, MemoryConfig.FlushTrigger.always(), StoreBackedPeriodicGate(store())), built, "s-throw")
        val before = requireNotNull(ledgerContent(owner)) { "the ledger needs one entry before the failing turn" }

        turn(
            flushMiddleware(manager, CountingModel(EXTRACTED, throws = true), MemoryConfig.FlushTrigger.always(), StoreBackedPeriodicGate(store())),
            built,
            "s-throw",
        )

        assertEquals(before, ledgerContent(owner), "a failed extraction must leave the ledger verbatim")
        assertNull(memoryMdContent(owner), "and must not curate anything")
    }
}
