package com.agnetix.harnax.harness.memory

import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `the injected block is delimited and names which layer it is`() {
        // What makes the block separable from everything else in the prompt: the conversation's own draft is
        // about to be merged and cleared, and the text here is the one that survives it, so the model has to be
        // able to tell the two apart. The other half of the clause — that both arrive — is the case below.
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
    fun `a long-term layer over its budget arrives cut rather than whole`() {
        // This block goes into every model call of the conversation, and the file it reads is kept under its
        // budget only by a model complying with its prompt. Upstream truncates the block it reads for exactly
        // this reason; an uncapped one here would let one owner's MEMORY.md grow the prompt without limit.
        val store = InMemoryStore()
        val text = "- kept\n".repeat(4_000)
        writeLongTerm(store, text)

        val built = prompt(LongTermMemoryContextMiddleware(domain(store)))

        assertTrue(built.contains("- kept"), "the head of the layer still arrives")
        assertTrue(built.contains(TRUNCATION_NOTICE), "a cut layer has to say it was cut")
        assertTrue(built.length < text.length, "the block is capped: ${built.length} against ${text.length}")
    }

    @Test
    fun `one conversation reads its owner's layer beside its own draft instead of as one text`() {
        // Design 11.10 clause 2, second half. Upstream's own <memory_context> answers from the two mounted
        // routes and is labelled by upstream, so what this class owns is the other half: the owner's curated
        // text has to arrive as its own block while the conversation's draft stays in the conversation's
        // bucket, and the two must not be folded into one undifferentiated memory.
        val store = InMemoryStore()
        writeLongTerm(store, "- the owner kept this across conversations")
        val sessionRoutes = domain(store).routes("sess-A")
        sessionRoutes.getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE).write(rc("sess-A"), "/MEMORY.md", "- this turn's own draft")

        val built = prompt(LongTermMemoryContextMiddleware(domain(store)))

        assertTrue(built.contains("- the owner kept this across conversations"), built)
        assertFalse(
            built.contains("- this turn's own draft"),
            "the conversation's un-merged draft is not the owner's block, and merging it in here would tell " +
                "the model something this conversation has not curated",
        )
        assertEquals(
            "- this turn's own draft",
            sessionRoutes.getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
                .read(rc("sess-A"), "/MEMORY.md", 0, 0)
                .fileData()?.content(),
            "the draft stays where the flush wrote it, which is what upstream's block then reads",
        )
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

        /** Spelled here rather than taken from production: the test has to notice the cut, not just find a constant. */
        const val TRUNCATION_NOTICE = "(long-term memory truncated)"
    }
}
