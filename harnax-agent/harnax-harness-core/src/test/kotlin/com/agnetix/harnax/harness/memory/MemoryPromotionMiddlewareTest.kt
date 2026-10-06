package com.agnetix.harnax.harness.memory

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
 * When a conversation's layer gets drained, and what a user feels when it does not.
 *
 * The trigger is a per-conversation throttle fired after the answer (design 11.4), and the two properties that
 * make it safe are both measurable here rather than in [MemoryPromoterTest]: nothing on the answer path may
 * wait on a model call nobody asked for, and one window per conversation is what lets two hot conversations of
 * the same owner each keep their own memory without either one holding the other's clock. The gate is the real
 * store-backed one, because a fake that always says yes would test the throttle by turning it off.
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

    private fun domain(store: BaseStore) = MemoryDomain(store, tenantId, owner, agentId, true)

    private fun middleware(store: BaseStore, gate: PeriodicGate, sessionId: String, answer: String) = MemoryPromotionMiddleware(
        MemoryPromoter(domain(store), sessionId, FixedModel(answer)),
        gate,
        window,
        sessionId,
    )

    private fun rc(sessionId: String) = RuntimeContext.builder().sessionId(sessionId).build()

    private fun writeLedger(store: BaseStore, sessionId: String, name: String, text: String) {
        domain(store).routes(sessionId).getValue(MemoryFilesystemRoutes.MEMORY_DIR_ROUTE)
            .write(rc(sessionId), "/$name", text)
    }

    private fun ledgers(store: BaseStore, sessionId: String) = store.search(domain(store).ledgerNamespace(sessionId), 100, 0).map { it.key() }

    /** One turn through the middleware, and how long the caller waited for it to complete. */
    private fun turn(middleware: MemoryPromotionMiddleware, sessionId: String = "sess-A"): Long {
        val started = System.nanoTime()
        middleware.onAgent(
            mock(Agent::class.java),
            rc(sessionId),
            AgentInput(listOf(Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text("hi").build()).build())),
            Function { Flux.empty<AgentEvent>() },
        ).blockLast(Duration.ofSeconds(10))
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }

    /** Waits for whatever the turn dispatched, the way [io.agentscope.harness.agent.HarnessAgent.close] does. */
    private fun awaitBackground() {
        assertTrue(MemoryBackgroundTasks.awaitQuiescence(10, TimeUnit.SECONDS), "the dispatched promotion never finished")
    }

    @Test
    fun `the answer does not wait for the merge`() {
        // The claim is a store round trip and the merge is a model call, both after every turn of a hot
        // conversation. A user must not pay for either, and the in-flight counter still has to cover the work
        // so a shutdown cannot release the workspace under a merge mid-write.
        val store = InMemoryStore()
        writeLedger(store, "sess-A", "2026-10-05.md", "- the owner wants terse answers")
        val gate = FakeGate(claimMillis = 500)

        val elapsed = turn(middleware(store, gate, "sess-A", "- terse answers"))
        awaitBackground()

        assertTrue(elapsed < 500, "the turn completed in ${elapsed}ms while the gate was still busy")
        assertEquals("- terse answers", domain(store).longTermCurated())
        assertEquals(
            listOf("memory-promotion:SESSION:sess-A" to window),
            gate.slots,
            "the slot is the conversation's own, at the configured window",
        )
    }

    @Test
    fun `a turn inside the window promotes nothing`() {
        val store = InMemoryStore()
        writeLedger(store, "sess-A", "2026-10-05.md", "- from this conversation")

        turn(middleware(store, FakeGate(allow = false), "sess-A", "- merged"))
        awaitBackground()

        assertEquals(listOf("/2026-10-05.md"), ledgers(store, "sess-A"), "a refused claim must not drain the layer")
        assertNull(domain(store).longTermCurated())
    }

    @Test
    fun `two conversations of one agent promote on their own clocks`() {
        // The third hard rule of 11.4, measured on the gate production actually hands the middleware: each
        // conversation has its own slot, so a hot conversation is never held behind a sibling that promoted a
        // minute ago — and its own second turn still is.
        val store = InMemoryStore()
        val gate = StoreBackedPeriodicGate(store)
        writeLedger(store, "sess-A", "2026-10-05.md", "- from A")
        writeLedger(store, "sess-B", "2026-10-05.md", "- from B")

        turn(middleware(store, gate, "sess-A", "- merged A"), "sess-A")
        awaitBackground()
        turn(middleware(store, gate, "sess-B", "- merged B"), "sess-B")
        awaitBackground()

        assertEquals("- merged B", domain(store).longTermCurated(), "B promoted on its own clock, not A's")
        assertEquals(emptyList<String>(), ledgers(store, "sess-B"))

        writeLedger(store, "sess-A", "2026-10-06.md", "- A again")
        turn(middleware(store, gate, "sess-A", "- should not run"))
        awaitBackground()

        assertTrue(ledgers(store, "sess-A").contains("/2026-10-06.md"), "A is still inside its own window")
    }

    @Test
    fun `a gate that cannot answer costs the turn nothing`() {
        // The store-backed gate already swallows its own failures, and the promotion runs off the answer path
        // anyway — but the pairing of the in-flight counter with the dispatch is what lets close() wait, so a
        // throw before the work starts must still release it rather than hang a shutdown forever.
        val store = InMemoryStore()
        writeLedger(store, "sess-A", "2026-10-05.md", "- from this conversation")
        val throwing = object : PeriodicGate {
            override fun tryClaim(name: String, minGap: Duration): Boolean = throw IllegalStateException("minio is down")
        }

        turn(middleware(store, throwing, "sess-A", "- merged"))
        awaitBackground()

        assertNull(domain(store).longTermCurated())
        assertEquals(listOf("/2026-10-05.md"), ledgers(store, "sess-A"))
    }
}
