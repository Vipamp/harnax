package com.agnetix.harnax.harness.memory

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The session layer's bucket key, which harnax-admin's memory page decodes without seeing this code.
 *
 * Admin reproduces `[tenants/<t>/]users/<u>/agents/<a>[/sessions/<sid>]/<segment>` in
 * `MemoryObjectKeys.locationOf`, and a key that drifts by one segment is a conversation's memory the page
 * can neither list nor delete. `MemoryObjectKeyCrossCheckTest` answers these literals against a live MinIO;
 * this class is the cheap half of the same contract, on the same store type the routes write through.
 */
class MemorySessionBucketKeyTest {

    private val tenantId = 4L
    private val owner = "u-1"
    private val agentId = "Research"

    private fun rc(sessionId: String) = RuntimeContext.builder().sessionId(sessionId).build()

    private fun sessionRoutes(
        store: InMemoryStore,
        sessionId: String,
        tenantScoped: Boolean = true,
    ) = MemoryFilesystemRoutes.sessionRoutes(store, tenantId, owner, agentId, sessionId, tenantScoped)

    private fun namespace(
        sessionId: String,
        segment: String,
        tenantScoped: Boolean = true,
    ) = MemoryFilesystemRoutes.sessionNamespace(tenantId, owner, agentId, sessionId, tenantScoped, segment)

    @Test
    fun `a conversation writes its own bucket one segment deeper`() {
        val store = InMemoryStore()

        val written = sessionRoutes(store, "sess-A")
            .getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
            .write(rc("sess-A"), "/MEMORY.md", "- still deciding on the format")

        assertTrue(written.isSuccess, "the write should have landed: ${written.error()}")
        assertNotNull(
            store.get(namespace("sess-A", "root"), "/MEMORY.md"),
            "the session layer is the root route under the session segment",
        )
        assertEquals(
            listOf("tenants", "4", "users", "u-1", "agents", "Research", "sessions", "sess-A", "root"),
            namespace("sess-A", "root"),
        )
    }

    @Test
    fun `the session ledger shares that bucket under its own segment`() {
        val store = InMemoryStore()

        sessionRoutes(store, "sess-A")
            .getValue(MemoryFilesystemRoutes.MEMORY_DIR_ROUTE)
            .write(rc("sess-A"), "/2026-10-05.md", "- entry")

        assertNotNull(
            store.get(namespace("sess-A", "memory"), "/2026-10-05.md"),
            "the daily ledger of a conversation stays inside that conversation",
        )
    }

    @Test
    fun `two sessions of one owner write two buckets`() {
        val store = InMemoryStore()

        sessionRoutes(store, "sess-A")
            .getValue(MemoryFilesystemRoutes.MEMORY_DIR_ROUTE)
            .write(rc("sess-A"), "/2026-10-05.md", "- from the first")
        sessionRoutes(store, "sess-B")
            .getValue(MemoryFilesystemRoutes.MEMORY_DIR_ROUTE)
            .write(rc("sess-B"), "/2026-10-05.md", "- from the second")

        val both = store.search(listOf("tenants", "4", "users", owner, "agents", agentId), 10, 0)
        assertEquals(2, both.size, "one day, two conversations, two ledgers: ${both.map { it.key() }}")
    }

    @Test
    fun `a session nests inside its agent so one agent prefix covers both layers`() {
        val store = InMemoryStore()

        MemoryFilesystemRoutes.routes(store, tenantId, owner, agentId, true)
            .getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
            .write(rc("sess-A"), "/MEMORY.md", "- long term")
        sessionRoutes(store, "sess-A")
            .getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
            .write(rc("sess-A"), "/MEMORY.md", "- session term")

        // Deleting `agents/Research/` is how the memory page reclaims a conversation's layer, and that only
        // works while the session segment comes after the agent segment rather than beside it.
        val underAgent = store.search(listOf("tenants", "4", "users", owner, "agents", agentId), 10, 0)
        assertEquals(2, underAgent.size, "found ${underAgent.map { it.key() }}")
    }

    @Test
    fun `turning the tenant segment off moves the session key the same way`() {
        val store = InMemoryStore()

        sessionRoutes(store, "sess-A", tenantScoped = false)
            .getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
            .write(rc("sess-A"), "/MEMORY.md", "- x")

        assertEquals(
            listOf("users", "u-1", "agents", "Research", "sessions", "sess-A", "root"),
            namespace("sess-A", "root", tenantScoped = false),
        )
        assertNotNull(store.get(namespace("sess-A", "root", tenantScoped = false), "/MEMORY.md"))
        assertNull(
            store.get(namespace("sess-A", "root"), "/MEMORY.md"),
            "and nothing lands under a tenant while the switch is off",
        )
    }

    @Test
    fun `the long-term bucket key is unchanged by the session layer`() {
        // The regression anchor: an agent whose session layer stays off addresses exactly yesterday's keys,
        // so the bucket, the page and the delete sweeps all keep pointing at the same objects.
        assertEquals(
            listOf("tenants", "4", "users", "u-1", "agents", "Research", "root"),
            MemoryFilesystemRoutes.namespace(tenantId, owner, agentId, true, "root"),
        )
        assertEquals(
            listOf("users", "u-1", "agents", "Research", "memory"),
            MemoryFilesystemRoutes.namespace(tenantId, owner, agentId, false, "memory"),
        )
    }

    @Test
    fun `the domain hands out whichever bucket it was bound to`() {
        // The launcher mounts what this returns and nothing else decides which layer a conversation extracts
        // into, so the two calls have to agree with the tuple the same domain gives the progress store.
        val store = InMemoryStore()
        val domain = MemoryDomain(store, tenantId, owner, agentId, true)

        domain.routes("sess-A").getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
            .write(rc("sess-A"), "/MEMORY.md", "- this conversation")
        domain.routes().getValue(MemoryFilesystemRoutes.MEMORY_DIR_ROUTE)
            .write(rc("sess-A"), "/2026-10-05.md", "- the owner")

        assertNotNull(store.get(domain.namespace("sess-A") + "root", "/MEMORY.md"), "the conversation's own draft")
        assertNotNull(store.get(domain.namespace(null) + "memory", "/2026-10-05.md"), "the owner's ledger stays the owner's")
        assertNull(store.get(domain.namespace("sess-A") + "memory", "/2026-10-05.md"))
        assertNull(store.get(domain.namespace(null) + "root", "/MEMORY.md"))
    }
}
