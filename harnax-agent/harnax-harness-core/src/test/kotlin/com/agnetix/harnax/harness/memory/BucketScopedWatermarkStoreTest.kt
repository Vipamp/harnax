package com.agnetix.harnax.harness.memory

import com.agnetix.harnax.harness.minio.StoreCasProbe
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore
import io.agentscope.harness.agent.memory.MemoryConsolidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The consolidation progress, kept per bucket instead of per deployment.
 *
 * Upstream stores "what time has this memory been consolidated to" at one fixed address in the shared store
 * — `memory/consolidation`, with no owner in it — so every user and every agent of a deployment reads and
 * writes the same object. One owner's consolidation therefore advances the progress that decides which of
 * *another* owner's daily entries are old enough to merge, and those entries are skipped in silence. A
 * bucket-scoped memory domain makes that worse rather than better: the session layer and the long-term layer
 * of one agent would be consolidated against the same progress, and whichever ran last would mark the other
 * one's entries as already handled.
 *
 * [BucketScopedWatermarkStore] is the contained fix, in the same shape as
 * [com.agnetix.harnax.harness.minio.ProcessLocalCoordinationStore]: intercept exactly one namespace, pass
 * every other byte through. Every case below is about which address a read or a compare-and-set ends up at,
 * because that is the whole of the defect — the pipeline, the prompt and the file layout do not move.
 */
class BucketScopedWatermarkStoreTest {

    private val ownerA = listOf("tenants", "4", "users", "alice", "agents", "Research")
    private val ownerB = listOf("tenants", "4", "users", "bob", "agents", "Research")

    /** The conversation's own bucket of the same agent: the owner tuple plus one pair of segments. */
    private val sessionA = ownerA + listOf(MemoryFilesystemRoutes.SESSIONS_SEGMENT, "sess-A")

    private fun scoped(
        delegate: InMemoryStore,
        bucket: List<String>,
    ): BucketScopedWatermarkStore = BucketScopedWatermarkStore(delegate, bucket)

    /** What upstream writes: one epoch-millis number under `ts`, at its own fixed address. */
    private fun ts(
        millis: Long,
    ): Map<String, Any> = mapOf("ts" to millis)

    @Test
    fun `upstream still keeps its progress at one address with no owner in it`() {
        // The wrapper intercepts that address by name, so the name is a contract with a class this module
        // does not own. If upstream moves it, the interception stops and every bucket silently shares again.
        assertEquals(listOf("memory", "consolidation"), upstreamField("WATERMARK_NAMESPACE"))
        assertEquals("watermark", upstreamField("WATERMARK_KEY"))
        assertEquals(listOf("memory", "consolidation"), BucketScopedWatermarkStore.UPSTREAM_NAMESPACE)
        assertEquals("watermark", BucketScopedWatermarkStore.UPSTREAM_KEY)
    }

    @Test
    fun `the progress lands in the bucket whose assembly wrote it`() {
        val delegate = InMemoryStore()

        scoped(delegate, ownerA).put(BucketScopedWatermarkStore.UPSTREAM_NAMESPACE, BucketScopedWatermarkStore.UPSTREAM_KEY, ts(1_000L))

        assertNotNull(
            delegate.get(ownerA + listOf(MemoryFilesystemRoutes.MEMORY_SEGMENT), BucketScopedWatermarkStore.UPSTREAM_KEY),
            "the entry goes beside the ledgers it counts, inside this owner's bucket",
        )
        assertNull(
            delegate.get(BucketScopedWatermarkStore.UPSTREAM_NAMESPACE, BucketScopedWatermarkStore.UPSTREAM_KEY),
            "and nothing is left at the shared address",
        )
    }

    @Test
    fun `a read of the progress comes back from the same bucket it was written to`() {
        val delegate = InMemoryStore()
        val store = scoped(delegate, ownerA)

        store.put(BucketScopedWatermarkStore.UPSTREAM_NAMESPACE, BucketScopedWatermarkStore.UPSTREAM_KEY, ts(2_000L))

        val read = store.get(BucketScopedWatermarkStore.UPSTREAM_NAMESPACE, BucketScopedWatermarkStore.UPSTREAM_KEY)
        assertEquals(1L, read?.version, "a first write is version 1, which is what its next CAS will compare")
        assertEquals(
            2_000L,
            read?.value()?.get("ts"),
            "upstream reads the timestamp back through the same two arguments it wrote with",
        )
    }

    @Test
    fun `two owners of one agent each have their own progress`() {
        val delegate = InMemoryStore()

        scoped(delegate, ownerA).put(BucketScopedWatermarkStore.UPSTREAM_NAMESPACE, BucketScopedWatermarkStore.UPSTREAM_KEY, ts(3_000L))

        assertNull(
            scoped(delegate, ownerB).get(BucketScopedWatermarkStore.UPSTREAM_NAMESPACE, BucketScopedWatermarkStore.UPSTREAM_KEY),
            "a second owner has to start at EPOCH, not at the moment the first one consolidated",
        )
        assertEquals(1, delegate.size(), "one object in the whole store, not two sharing one address: ${keysOf(delegate)}")
    }

    @Test
    fun `the session layer and the long-term layer do not consolidate against one progress`() {
        // The case the layering adds: both buckets belong to one owner and one agent, and a conversation that
        // marks its entries handled must not speak for the owner's cross-session memory.
        val delegate = InMemoryStore()

        scoped(delegate, sessionA).put(BucketScopedWatermarkStore.UPSTREAM_NAMESPACE, BucketScopedWatermarkStore.UPSTREAM_KEY, ts(4_000L))

        assertNull(
            scoped(delegate, ownerA).get(BucketScopedWatermarkStore.UPSTREAM_NAMESPACE, BucketScopedWatermarkStore.UPSTREAM_KEY),
            "the long-term bucket has not been consolidated just because a conversation's was",
        )
        assertNotNull(
            delegate.get(sessionA + listOf(MemoryFilesystemRoutes.MEMORY_SEGMENT), BucketScopedWatermarkStore.UPSTREAM_KEY),
            "and the conversation's progress sits in the conversation's bucket",
        )
    }

    @Test
    fun `a compare-and-set contends only inside its own bucket`() {
        // Upstream writes the progress with putIfVersion and gives up after five losses, logging a warning
        // nobody reads. On the shared address that is exactly how an owner's entries get skipped forever: the
        // loser re-reads a timestamp somebody else advanced, and consolidates against that.
        val delegate = InMemoryStore()
        val ns = BucketScopedWatermarkStore.UPSTREAM_NAMESPACE
        val key = BucketScopedWatermarkStore.UPSTREAM_KEY

        assertTrue(scoped(delegate, ownerA).putIfVersion(ns, key, ts(1L), 0L))
        assertTrue(
            scoped(delegate, ownerB).putIfVersion(ns, key, ts(2L), 0L),
            "a first write in another bucket is not a version conflict with the first one",
        )
        assertTrue(
            !scoped(delegate, ownerA).putIfVersion(ns, key, ts(3L), 0L),
            "and inside one bucket a stale version is still refused, or the progress loses updates",
        )
    }

    @Test
    fun `every other namespace goes through untouched`() {
        val delegate = InMemoryStore()
        val store = scoped(delegate, ownerA)
        val ledger = ownerA + listOf(MemoryFilesystemRoutes.MEMORY_SEGMENT)

        store.put(ledger, "/2026-10-05.md", mapOf("content" to "- entry"))
        store.put(StoreCasProbe.COORDINATION, "memory-flush:SESSION:sess-A", mapOf("lastClaimAt" to 9L))
        store.delete(BucketScopedWatermarkStore.UPSTREAM_NAMESPACE, BucketScopedWatermarkStore.UPSTREAM_KEY)
        assertEquals(
            listOf("/2026-10-05.md", "memory-flush:SESSION:sess-A"),
            keysOf(delegate).sorted(),
            "the memory files and the throttle slots keep their own addresses, and deleting a progress that " +
                "was never written leaves nothing behind",
        )
    }

    private fun keysOf(store: InMemoryStore): List<String> = store.search(listOf(), 100, 0).map { it.key() }

    private fun upstreamField(
        name: String,
    ): Any? = MemoryConsolidator::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null)
}
