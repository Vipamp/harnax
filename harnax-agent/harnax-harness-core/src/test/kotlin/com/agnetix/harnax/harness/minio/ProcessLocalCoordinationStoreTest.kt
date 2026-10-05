package com.agnetix.harnax.harness.minio

import io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * The gate [StoreCasProbe] rejects has to be replaced, not just announced: upstream builds
 * `StoreBackedPeriodicGate` whenever a distributed store exists and gives the assembly no way to choose
 * the gate, so the only lever is the store it is handed.
 *
 * [ProcessLocalCoordinationStore] is that lever. It answers the coordination namespace in this process
 * and forwards every other key to the remote store, which is what keeps the memory bucket itself remote
 * — only the throttle that deduplicates background consolidation becomes per-replica, which is exactly
 * what upstream's own `LocalPeriodicGate` fallback means.
 */
class ProcessLocalCoordinationStoreTest {

    @Test
    fun `the fallback passes the same probe that rejected the store it stands in for`() {
        // This is the whole point of the shape: the decorator has to satisfy the contract upstream's gate
        // relies on, whatever the store behind it answers.
        val support = StoreCasProbe.probe(ProcessLocalCoordinationStore(RecordingStore()))

        assertTrue(support.supported, support.detail)
    }

    @Test
    fun `a coordination claim goes through once and then waits out its gap`() {
        val gate = StoreBackedPeriodicGate(ProcessLocalCoordinationStore(RecordingStore()))
        val slot = "memory-flush:SESSION:" + UUID.randomUUID()

        assertTrue(gate.tryClaim(slot, Duration.ofHours(1)), "the first claim of a fresh slot must win")
        assertFalse(gate.tryClaim(slot, Duration.ofHours(1)), "and inside its gap nobody else may run it again")
    }

    @Test
    fun `two agent instances in one replica are throttled together`() {
        // Upstream's own local fallback keys a static map for the same reason
        // (`LocalPeriodicGate.SHARED_LAST_CLAIM_AT`), and a per-instance map would silently multiply the
        // consolidation work by the live session count.
        val slot = "memory-maintenance:SESSION:" + UUID.randomUUID()
        val first = StoreBackedPeriodicGate(ProcessLocalCoordinationStore(RecordingStore()))
        val second = StoreBackedPeriodicGate(ProcessLocalCoordinationStore(RecordingStore()))

        assertTrue(first.tryClaim(slot, Duration.ofHours(1)))
        assertFalse(
            second.tryClaim(slot, Duration.ofHours(1)),
            "a second agent instance in the same process must lose the claim the first one took",
        )
    }

    @Test
    fun `coordination slots never reach the remote store`() {
        val delegate = RecordingStore()
        val store = ProcessLocalCoordinationStore(delegate)
        val key = "probe-" + UUID.randomUUID()

        store.put(StoreCasProbe.COORDINATION, key, mapOf("lastClaimAt" to 0L))
        store.get(StoreCasProbe.COORDINATION, key)
        store.putIfVersion(StoreCasProbe.COORDINATION, key, mapOf("lastClaimAt" to 1L), 1L)
        store.search(StoreCasProbe.COORDINATION, 10, 0)
        store.delete(StoreCasProbe.COORDINATION, key)

        assertEquals(
            emptyList<String>(),
            delegate.calls,
            "a store that cannot be trusted to compare versions must not be written at all by the fallback",
        )
    }

    @Test
    fun `every other key still goes to the remote store`() {
        // The memory bucket is the reason this domain exists. A fallback that swallowed it would turn a
        // broken throttle into a memory that dies with the replica — worse than the thing it repairs.
        val delegate = RecordingStore()
        val store = ProcessLocalCoordinationStore(delegate)
        val value = mapOf("content" to "likes terse answers")

        store.put(BUCKET, "MEMORY.md", value)
        val read = store.get(BUCKET, "MEMORY.md")
        store.search(BUCKET, 10, 0)
        store.delete(BUCKET, "MEMORY.md")
        val claimed = store.putIfVersion(BUCKET, "MEMORY.md", value, 7L)

        assertEquals(
            listOf(
                "put:$BUCKET_KEY:MEMORY.md",
                "get:$BUCKET_KEY:MEMORY.md",
                "search:$BUCKET_KEY",
                "delete:$BUCKET_KEY:MEMORY.md",
                "putIfVersion:$BUCKET_KEY:MEMORY.md",
            ),
            delegate.calls,
        )
        assertEquals("from the remote store", read?.value?.get("content"), "the answer comes from the delegate untouched")
        assertEquals(7L, read?.version, "the version of a delegated key is the delegate's version")
        assertTrue(claimed, "putIfVersion outside the coordination namespace is the delegate's to answer")
    }

    /** Records what it was asked to do, and answers reads with a fixed item so passthrough is visible. */
    private class RecordingStore : BaseStore {
        val calls = mutableListOf<String>()

        override fun get(namespace: List<String>, key: String): StoreItem? {
            calls += "get:${ns(namespace)}:$key"
            return StoreItem(key, mapOf("content" to "from the remote store"), 7L)
        }

        override fun put(namespace: List<String>, key: String, value: Map<String, Any>) {
            calls += "put:${ns(namespace)}:$key"
        }

        override fun putIfVersion(
            namespace: List<String>,
            key: String,
            value: Map<String, Any>,
            expectedVersion: Long,
        ): Boolean {
            calls += "putIfVersion:${ns(namespace)}:$key"
            return true
        }

        override fun search(namespace: List<String>, limit: Int, offset: Int): List<StoreItem> {
            calls += "search:${ns(namespace)}"
            return emptyList()
        }

        override fun delete(namespace: List<String>, key: String) {
            calls += "delete:${ns(namespace)}:$key"
        }

        private fun ns(namespace: List<String>): String = namespace.joinToString("/")
    }

    companion object {
        /** A memory bucket: the owner-bucket namespace the memory routes are mounted under. */
        private val BUCKET = listOf("tenants", "1", "users", "alice", "agents", "bot", "root")
        private val BUCKET_KEY = BUCKET.joinToString("/")
    }
}
