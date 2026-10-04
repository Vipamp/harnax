package com.agnetix.harnax.harness.memory

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The memory bucket, on the real store type the harness routes write through.
 *
 * The bucket is `tenants/<id>/users/<uid>/agents/<agentId>` plus the route's own segment, and the owner
 * half of that tuple is fixed when the route is built: `MEMORY.md` and the daily ledger of one agent share
 * it, while everything else the agent writes keeps the isolation scope it has today. `InMemoryStore` stands
 * in for MinIO here because the assertion is about the namespace the route asks for, not about conditional
 * writes — those are covered against a live MinIO in `MinioBaseStoreCasTest`.
 */
class MemoryFilesystemRoutesTest {

    private val tenantId = 4L
    private val owner = "u-1"
    private val agentId = "Research"

    /** What the runtime really hands a call: a conversation, no user. */
    private fun rc(sessionId: String = "sess-1") = RuntimeContext.builder().sessionId(sessionId).build()

    private fun routes(
        store: InMemoryStore,
        owner: String = this.owner,
        tenantId: Long = this.tenantId,
        tenantScoped: Boolean = true,
    ) = MemoryFilesystemRoutes.routes(store, tenantId, owner, agentId, tenantScoped)

    @Test
    fun `a write lands in the bucket the route was assembled for`() {
        val store = InMemoryStore()

        val written = routes(store).getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)
            .write(rc(), "/MEMORY.md", "- the user likes terse answers")

        assertTrue(written.isSuccess, "the write should have landed: ${written.error()}")
        assertNotNull(
            store.get(listOf("tenants", "4", "users", "u-1", "agents", "Research", "root"), "/MEMORY.md"),
            "MEMORY.md belongs to the owner bucket's root segment",
        )
    }

    @Test
    fun `the daily ledger shares that bucket under its own segment`() {
        val store = InMemoryStore()

        routes(store).getValue(MemoryFilesystemRoutes.MEMORY_DIR_ROUTE)
            .write(rc(), "/2026-10-05.md", "- entry")

        assertNotNull(
            store.get(listOf("tenants", "4", "users", "u-1", "agents", "Research", "memory"), "/2026-10-05.md"),
            "the ledger is keyed by owner, not by session",
        )
    }

    @Test
    fun `two sessions of one owner write the same ledger key`() {
        val store = InMemoryStore()
        val ledger = routes(store).getValue(MemoryFilesystemRoutes.MEMORY_DIR_ROUTE)

        ledger.write(rc("sess-1"), "/2026-10-05.md", "- from the first")
        ledger.write(rc("sess-2"), "/2026-10-05.md", "- from the second")

        val items = store.search(listOf("tenants", "4", "users", "u-1", "agents", "Research", "memory"), 10, 0)
        assertEquals(1, items.size, "cross-session memory means one file per owner per day, found ${items.map { it.key() }}")
    }

    @Test
    fun `one userId in two tenants gets two buckets that never see each other`() {
        val store = InMemoryStore()

        routes(store, tenantId = 4L).getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE).write(rc(), "/MEMORY.md", "- from tenant 4")
        routes(store, tenantId = 5L).getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE).write(rc(), "/MEMORY.md", "- from tenant 5")

        val bucket4 = store.search(listOf("tenants", "4", "users", "u-1", "agents", agentId, "root"), 10, 0)
        assertEquals(1, bucket4.size, "found ${bucket4.map { it.key() }}")
        assertEquals("- from tenant 4", bucket4.single().value()["content"], "the same user id must not read across tenants")
        assertEquals(2, store.size(), "two buckets of one object each, found ${store.search(listOf(), 10, 0).map { it.key() }}")
    }

    @Test
    fun `the call cannot redirect memory into somebody else's bucket`() {
        // The owner is not read off the call, so no value a call carries can move these bytes. Harnax keeps
        // one agent per owner (`DefaultAgentRunner.cachedAgent` drops an entry built for another user),
        // and this is what that guarantee buys: a mis-threaded context writes the owner it belongs to.
        val store = InMemoryStore()
        val memoryMd = routes(store).getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE)

        val write = memoryMd.write(
            RuntimeContext.builder().userId("someone-else").sessionId("sess-1").build(),
            "/MEMORY.md",
            "- still the owner's line",
        )

        assertTrue(write.isSuccess, "the route does not ask the call who is writing: ${write.error()}")
        assertEquals(
            "- still the owner's line",
            store.get(listOf("tenants", "4", "users", "u-1", "agents", "Research", "root"), "/MEMORY.md")?.value()?.get("content"),
            "the bytes land under the assembled owner",
        )
        assertEquals(0, store.search(listOf("tenants", "4", "users", "someone-else", "agents", "Research", "root"), 10, 0).size, "and nowhere else")
    }

    @Test
    fun `turning the tenant segment off keeps owners apart and tenants merged`() {
        val store = InMemoryStore()

        routes(store, tenantScoped = false).getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE).write(rc(), "/MEMORY.md", "- x")

        assertNotNull(
            store.get(listOf("users", "u-1", "agents", "Research", "root"), "/MEMORY.md"),
            "without the tenant segment the owner bucket starts at users",
        )
        assertNull(
            store.get(listOf("tenants", "4", "users", "u-1", "agents", "Research", "root"), "/MEMORY.md"),
            "and nothing is written under a tenant",
        )
    }

    @Test
    fun `the two routes the memory domain owns`() {
        val routes = routes(InMemoryStore())

        assertEquals(setOf("MEMORY.md", "memory/"), routes.keys, "only the memory files move out of the session bucket")
    }
}
