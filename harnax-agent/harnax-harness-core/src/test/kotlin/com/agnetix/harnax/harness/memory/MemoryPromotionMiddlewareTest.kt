package com.agnetix.harnax.harness.memory

import com.agnetix.harnax.agent.adaptor.MemoryDraftAdaptor
import com.agnetix.harnax.agent.adaptor.MemoryDraftIntake
import com.agnetix.harnax.agent.adaptor.MemoryDraftProposal
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.message.ContentBlock
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.TextBlock
import io.agentscope.core.middleware.AgentInput
import io.agentscope.core.model.ChatResponse
import io.agentscope.core.model.Model
import io.agentscope.harness.agent.coordination.PeriodicGate
import io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.Function

/**
 * When a conversation's layer gets proposed, and what a user feels when it does not.
 *
 * The trigger is a per-conversation throttle fired after the answer (design 11.4), and the two properties that
 * make it safe are both measurable here rather than in [MemoryPromoterTest]: nothing on the answer path may
 * wait on a model call nobody asked for, and one window per conversation is what lets two hot conversations of
 * the same owner each keep their own memory without either one holding the other's clock. The gate is the real
 * store-backed one, because a fake that always says yes would test the throttle by turning it off.
 *
 * What the fired attempt produced is read from the queue, not from the bucket: this stage hands a merge to a
 * person and stops. The fake intake therefore only remembers candidates — an approval writing the owner's layer
 * here would test Admin, and this class has no way to grant one.
 */
class MemoryPromotionMiddlewareTest {

    private val tenantId = 4L
    private val owner = "1"
    private val agentId = "Research"
    private val window = Duration.ofMinutes(30)

    private class FixedModel(private val answer: String) : Model {
        override fun stream(
            messages: List<Msg>,
            tools: List<io.agentscope.core.model.ToolSchema>?,
            options: io.agentscope.core.model.GenerateOptions?,
        ): Flux<ChatResponse> = Flux.just(
            ChatResponse.builder().content(listOf<ContentBlock>(TextBlock.builder().text(answer).build())).build(),
        )

        override fun getModelName(): String = "fixed"
    }

    private class FakeGate(private val allow: Boolean = true, private val claimMillis: Long = 0) : PeriodicGate {
        val slots = mutableListOf<Pair<String, Duration>>()

        override fun tryClaim(name: String, minGap: Duration): Boolean {
            slots.add(name to minGap)
            if (claimMillis > 0) Thread.sleep(claimMillis)
            return allow
        }
    }

    /** A queue that accepts and remembers, so a turn's output is visible without anyone approving it. */
    private class RecordingQueue : MemoryDraftAdaptor {
        val proposals = mutableListOf<MemoryDraftProposal>()

        override fun propose(proposal: MemoryDraftProposal): MemoryDraftIntake {
            proposals.add(proposal)
            return MemoryDraftIntake.Queued(proposals.size.toLong())
        }
    }

    private fun domain(store: BaseStore) = MemoryDomain(store, tenantId, owner, agentId, true)

    private fun middleware(
        store: BaseStore,
        gate: PeriodicGate,
        sessionId: String,
        answer: String,
        queue: RecordingQueue,
    ) = MemoryPromotionMiddleware(
        MemoryPromoter(domain(store), sessionId, FixedModel(answer), queue),
        gate,
        window,
        sessionId,
    )

    private fun rc(sessionId: String) = RuntimeContext.builder().sessionId(sessionId).build()

    /** Built per test rather than per turn: the first mock a JVM makes costs more than the turn being timed. */
    private val agent = mock(Agent::class.java)

    private fun writeLedger(store: BaseStore, sessionId: String, name: String, text: String) {
        domain(store).routes(sessionId).getValue(MemoryFilesystemRoutes.MEMORY_DIR_ROUTE)
            .write(rc(sessionId), "/$name", text)
    }

    private fun ledgers(store: BaseStore, sessionId: String) = store.search(domain(store).ledgerNamespace(sessionId), 100, 0).map { it.key() }

    /** One turn through the middleware, and how long the caller waited for it to complete. */
    private fun turn(middleware: MemoryPromotionMiddleware, sessionId: String = "sess-A"): Long {
        val started = System.nanoTime()
        middleware.onAgent(
            agent,
            rc(sessionId),
            AgentInput(listOf(Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text("hi").build()).build())),
            Function { Flux.empty<AgentEvent>() },
        ).blockLast(Duration.ofSeconds(10))
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }

    /** Waits for whatever the turn dispatched, the way [io.agentscope.harness.agent.HarnessAgent.close] does. */
    private fun awaitBackground() {
        assertTrue(MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS), "the dispatched proposal never finished")
    }

    @Test
    fun `the answer does not wait for the merge`() {
        // The claim is a store round trip and the merge is a model call plus an intake, both after every turn of
        // a hot conversation. A user must not pay for any of them, and the in-flight counter still has to cover
        // the work so a shutdown cannot release the workspace under a merge mid-flight.
        val store = InMemoryStore()
        writeLedger(store, "sess-A", "2026-10-05.md", "- the owner wants terse answers")
        val gate = FakeGate(claimMillis = 500)
        val queue = RecordingQueue()

        val elapsed = turn(middleware(store, gate, "sess-A", "- terse answers", queue))
        awaitBackground()

        assertTrue(elapsed < 500, "the turn completed in ${elapsed}ms while the gate was still busy")
        assertEquals("- terse answers", queue.proposals.single().mergedMarkdown)
        assertNull(domain(store).longTermCurated(), "the owner's layer waits for a person")
        assertEquals(
            listOf("memory-promotion:SESSION:sess-A" to window),
            gate.slots,
            "the slot is the conversation's own, at the configured window",
        )
    }

    @Test
    fun `a turn inside the window proposes nothing`() {
        val store = InMemoryStore()
        writeLedger(store, "sess-A", "2026-10-05.md", "- from this conversation")
        val queue = RecordingQueue()

        turn(middleware(store, FakeGate(allow = false), "sess-A", "- merged", queue))
        awaitBackground()

        assertEquals(listOf("/2026-10-05.md"), ledgers(store, "sess-A"), "a refused claim must not drain the layer")
        assertEquals(0, queue.proposals.size)
        assertNull(domain(store).longTermCurated())
    }

    @Test
    fun `two conversations of one agent propose on their own clocks`() {
        // The third hard rule of 11.4, measured on the gate production actually hands the middleware: each
        // conversation has its own slot, so a hot conversation is never held behind a sibling that proposed a
        // minute ago — and its own second turn still is.
        val store = InMemoryStore()
        val gate = StoreBackedPeriodicGate(store)
        val queue = RecordingQueue()
        writeLedger(store, "sess-A", "2026-10-05.md", "- from A")
        writeLedger(store, "sess-B", "2026-10-05.md", "- from B")

        turn(middleware(store, gate, "sess-A", "- merged A", queue), "sess-A")
        awaitBackground()
        turn(middleware(store, gate, "sess-B", "- merged B", queue), "sess-B")
        awaitBackground()

        assertEquals(
            listOf("sess-A", "sess-B"),
            queue.proposals.map { it.sessionId },
            "B proposed on its own clock, not A's",
        )
        assertEquals(listOf("/2026-10-05.md"), ledgers(store, "sess-B"), "and a proposal drains nothing on its own")

        writeLedger(store, "sess-A", "2026-10-06.md", "- A again")
        turn(middleware(store, gate, "sess-A", "- should not run", queue), "sess-A")
        awaitBackground()

        assertEquals(2, queue.proposals.size, "A is still inside its own window")
        assertTrue(ledgers(store, "sess-A").contains("/2026-10-06.md"))
    }

    @Test
    fun `a gate that cannot answer costs the turn nothing`() {
        // The store-backed gate already swallows its own failures, and the proposal runs off the answer path
        // anyway — but the pairing of the in-flight counter with the dispatch is what lets close() wait, so a
        // throw before the work starts must still release it rather than hang a shutdown forever.
        val store = InMemoryStore()
        writeLedger(store, "sess-A", "2026-10-05.md", "- from this conversation")
        val queue = RecordingQueue()
        val throwing = object : PeriodicGate {
            override fun tryClaim(name: String, minGap: Duration): Boolean = throw IllegalStateException("minio is down")
        }

        turn(middleware(store, throwing, "sess-A", "- merged", queue))
        awaitBackground()

        assertEquals(0, queue.proposals.size)
        assertNull(domain(store).longTermCurated())
        assertEquals(listOf("/2026-10-05.md"), ledgers(store, "sess-A"))
    }

    @Test
    fun `a first turn does not spend the window on a layer the extraction has not filled yet`() {
        // The ordering this asserts is production's: a turn's own extraction is dispatched after the answer and
        // needs a model round trip, so promotion looks at a layer that is still empty. Winning the claim at
        // that moment closes the window for 30 minutes, and a conversation that ends before then — which is
        // most of them — would never propose at all. A burned window is invisible here, so the test is the
        // whole point: the turn that finds the layer has content still has one.
        val store = InMemoryStore()
        val queue = RecordingQueue()
        val middleware = middleware(store, StoreBackedPeriodicGate(store), "sess-A", "- terse answers", queue)

        turn(middleware)
        awaitBackground()
        assertEquals(0, queue.proposals.size)

        writeLedger(store, "sess-A", "2026-10-05.md", "- the owner wants terse answers")
        turn(middleware)
        awaitBackground()

        assertEquals("- terse answers", queue.proposals.single().mergedMarkdown, "the second turn found its window open")
        assertEquals(listOf("/2026-10-05.md"), ledgers(store, "sess-A"), "while the layer it merged stays for the approval")
    }

    @Test
    fun `a conversation with nothing to promote does not ask the gate`() {
        // The gate has one method and a claim cannot be handed back, so the cheap read goes first. This is the
        // same rule as the test above, pinned at the seam rather than through a minute of store traffic.
        val store = InMemoryStore()
        val gate = FakeGate()
        val queue = RecordingQueue()

        turn(middleware(store, gate, "sess-A", "- merged", queue))
        awaitBackground()

        assertEquals(emptyList<Pair<String, Duration>>(), gate.slots, "an empty layer is no reason to close a window")
        assertEquals(0, queue.proposals.size)
        assertNull(domain(store).longTermCurated())
    }

    @Test
    fun `a layer that cannot be read leaves the window for whoever can`() {
        // An outage must not look like a spent window either: the next turn of the same conversation would be
        // throttled behind a claim that merged nothing.
        val gate = FakeGate()
        val queue = RecordingQueue()
        val unreadable = object : BaseStore {
            override fun get(namespace: List<String>, key: String): StoreItem = throw IllegalStateException("minio is down")

            override fun put(namespace: List<String>, key: String, value: Map<String, Any>) = Unit

            override fun putIfVersion(
                namespace: List<String>,
                key: String,
                value: Map<String, Any>,
                expectedVersion: Long,
            ) = true

            override fun search(namespace: List<String>, limit: Int, offset: Int): List<StoreItem> = throw IllegalStateException("minio is down")

            override fun delete(namespace: List<String>, key: String) = throw AssertionError("nothing may be cleared")
        }

        turn(middleware(unreadable, gate, "sess-A", "- merged", queue))
        awaitBackground()

        assertEquals(emptyList<Pair<String, Duration>>(), gate.slots, "a failed read is not a claim")
        assertEquals(0, queue.proposals.size)
    }
}
