package com.agnetix.harnax.harness.memory

import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * The long-term layer reaching a conversation that keeps its own draft.
 *
 * With the session layer mounted, `MEMORY.md` and `memory/` answer from the conversation's bucket, so
 * upstream's own `<memory_context>` — which reads exactly those two routes — shows the model this turn's
 * layer and nothing of the owner's curated one. The long-term layer is written only by promotion (design
 * 11.1), so it cannot take the same prefixes; it goes in as its own labelled block instead, read afresh
 * every call so a promotion from another conversation shows up without rebuilding the agent.
 */
class LongTermMemoryContextMiddlewareTest {

    private val tenantId = 4L
    private val owner = "1"
    private val agentId = "Research"

    private fun domain(store: BaseStore) = MemoryDomain(store, tenantId, owner, agentId, true)

    private fun rc(sessionId: String = "sess-A") = RuntimeContext.builder().sessionId(sessionId).build()

    private fun prompt(middleware: LongTermMemoryContextMiddleware, base: String = "answer") = requireNotNull(middleware.onSystemPrompt(mock(Agent::class.java), rc(), base).block())

    private fun writeLongTerm(store: BaseStore, text: String) {
        domain(store).routes().getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE).write(rc(), "/MEMORY.md", text)
    }

    @Test
    fun `a brand-new conversation still reads its owner's long-term layer`() {
        // Design 11.10 clause 2, first half: the first turn of a conversation that has nothing of its own yet
        // must still carry the cross-session memory, or the two-layer agent forgets everything its owner
        // already knows until the throttle has had its say.
        val store = InMemoryStore()
        writeLongTerm(store, "- the user prefers terse answers")

        val built = prompt(LongTermMemoryContextMiddleware(domain(store)))

        assertTrue(built.startsWith("answer"), "the agent's own prompt stays first, got: $built")
        assertTrue(built.contains("- the user prefers terse answers"), built)
    }

    @Test
    fun `the two layers arrive as two blocks the model can tell apart`() {
        // The same clause's second half: a conversation that has both must not read them as one undifferentiated
        // memory, because the session block is a draft that is about to be merged and cleared, and the
        // long-term block is the one that survives it.
        val store = InMemoryStore()
        writeLongTerm(store, "- kept")

        val built = prompt(LongTermMemoryContextMiddleware(domain(store)))

        assertTrue(built.contains(OPEN_TAG) && built.contains(CLOSE_TAG), "no delimiting tags in: $built")
        assertTrue(
            built.indexOf(OPEN_TAG) < built.indexOf("- kept") && built.indexOf("- kept") < built.indexOf(CLOSE_TAG),
            "the long-term text has to sit inside its own tags: $built",
        )
        assertTrue(built.contains("Long-term memory"), "the block has to name which layer it is: $built")
    }

    @Test
    fun `an owner with nothing curated gets the prompt untouched`() {
        // No objects at all is the ordinary state of a fresh owner, and an empty labelled block in every prompt
        // would teach the model that this agent has a long-term memory that is always blank.
        val store = InMemoryStore()

        assertEquals("answer", prompt(LongTermMemoryContextMiddleware(domain(store))))
    }

    @Test
    fun `a blank curated layer is no block either`() {
        val store = InMemoryStore()
        writeLongTerm(store, "   ")

        assertEquals("answer", prompt(LongTermMemoryContextMiddleware(domain(store))))
    }

    @Test
    fun `a store that cannot answer costs the turn nothing`() {
        // The read sits on the conversation path. A bucket that is unreachable has to cost the model its
        // long-term layer for this call, not the answer: upstream's own reader would have failed the prompt,
        // and this one is an addition rather than a replacement.
        val failing = object : BaseStore {
            override fun get(namespace: List<String>, key: String): StoreItem = throw IllegalStateException("minio is down")

            override fun put(namespace: List<String>, key: String, value: Map<String, Any>) = Unit
            override fun putIfVersion(namespace: List<String>, key: String, value: Map<String, Any>, expectedVersion: Long) = true
            override fun search(namespace: List<String>, limit: Int, offset: Int): List<StoreItem> = emptyList()
            override fun delete(namespace: List<String>, key: String) = Unit
        }

        assertEquals("answer", prompt(LongTermMemoryContextMiddleware(MemoryDomain(failing, tenantId, owner, agentId, true))))
    }

    @Test
    fun `the layer is read again every call`() {
        // An agent is built once per session and cached, so a block frozen at assembly would miss the
        // promotion this very conversation triggers, and every later one from a sibling conversation.
        val store = InMemoryStore()
        val middleware = LongTermMemoryContextMiddleware(domain(store))

        assertEquals("answer", prompt(middleware))
        writeLongTerm(store, "- merged from the first conversation")

        assertTrue(prompt(middleware).contains("- merged from the first conversation"), "a stale block would miss it")
    }

    @Test
    fun `the tenant segment off reads the bucket the routes write`() {
        // Both halves of one key root: a deployment that drops the tenant must not read a long-term layer that
        // no conversation's sibling writes.
        val store = InMemoryStore()
        val unscoped = MemoryDomain(store, tenantId, owner, agentId, false)
        unscoped.routes().getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE).write(rc(), "/MEMORY.md", "- unscoped")

        assertTrue(prompt(LongTermMemoryContextMiddleware(unscoped)).contains("- unscoped"))
        assertEquals(listOf("users", "1", "agents", "Research"), unscoped.namespace(null))
    }

    private companion object {
        const val OPEN_TAG = "<long_term_memory>"
        const val CLOSE_TAG = "</long_term_memory>"
    }
}
