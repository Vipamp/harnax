package com.agnetix.harnax.harness.minio

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What [StoreCasProbe] has to be able to tell apart, on stores that all answer `get`/`put` the same way
 * and differ only in what their `putIfVersion` actually does.
 *
 * The reason this is worth a round trip at assembly: upstream builds
 * `StoreBackedPeriodicGate(distributedStore.baseStore())` whenever a distributed store exists
 * (`HarnessAgent.java:2409-2412`) and there is no builder hook to choose otherwise, so whatever this
 * store answers decides whether the memory hooks ever run. Two opposite defects both end in a deployment
 * where consolidation never fires, and only one of them is loud.
 */
class StoreCasProbeTest {

    @Test
    fun `a store that really compares versions is supported`() {
        val support = StoreCasProbe.probe(HonestStore())

        assertTrue(support.supported, support.detail)
    }

    @Test
    fun `a store whose precondition is ignored by the backend is not supported`() {
        // The shape a gateway takes when it accepts If-Match / If-None-Match but does not enforce them:
        // every replica's claim "succeeds", so the gate deduplicates nothing. Reading that as healthy is
        // worse than the silent store below, because nothing ever complains.
        val support = StoreCasProbe.probe(LyingStore())

        assertFalse(support.supported, support.detail)
        assertTrue(
            support.detail.contains("ignored", ignoreCase = true),
            "the refusal has to name the precondition as the thing that did not hold: ${support.detail}",
        )
    }

    @Test
    fun `a store that inherits the interface default is not supported`() {
        // `BaseStore.putIfVersion` defaults to `return false` so backends can compile without overriding
        // it: every claim is refused, flush and maintenance both stop, and no error is ever raised.
        val support = StoreCasProbe.probe(NoCasStore())

        assertFalse(support.supported, support.detail)
        assertTrue(
            support.detail.contains("never", ignoreCase = true),
            "expected the reason to say the claim never went through: ${support.detail}",
        )
    }

    @Test
    fun `a store that throws while comparing is not supported and says what it threw`() {
        val support = StoreCasProbe.probe(ThrowingStore())

        assertFalse(support.supported, support.detail)
        assertTrue(
            support.detail.contains("boom"),
            "the server's own words belong in the reason an operator gets: ${support.detail}",
        )
    }

    @Test
    fun `a store that was not reached is no verdict about its compare-and-swap`() {
        // The caller keeps this answer for the whole process, so a verdict that really describes a down
        // connection would pin a healthy deployment to per-replica coordination after one restart.
        assertFalse(StoreCasProbe.probe(ThrowingStore()).definitive)
    }

    @Test
    fun `a store that answered and refused is a verdict worth keeping`() {
        assertTrue(StoreCasProbe.probe(NoCasStore()).definitive)
    }

    @Test
    fun `the probe leaves its own slots behind in no store`() {
        val store = HonestStore()

        StoreCasProbe.probe(store)

        assertEquals(
            emptyList<StoreItem>(),
            store.search(StoreCasProbe.COORDINATION, 10, 0),
            "a probe that leaves a slot behind would claim that throttle for whoever reads it next",
        )
    }

    /** Storage without a version comparison, i.e. exactly what the interface default gives. */
    private open class NoCasStore : BaseStore {
        protected val entries = LinkedHashMap<String, Stored>()

        protected data class Stored(val value: Map<String, Any>, val version: Long)

        override fun get(namespace: List<String>, key: String): StoreItem? = slot(namespace, key)?.second?.let { StoreItem(key, it.value, it.version) }

        override fun put(namespace: List<String>, key: String, value: Map<String, Any>) {
            val name = prefix(namespace) + key
            entries[name] = Stored(value, (entries[name]?.version ?: 0L) + 1L)
        }

        override fun search(namespace: List<String>, limit: Int, offset: Int): List<StoreItem> {
            val head = prefix(namespace)
            return entries
                .filterKeys { it.startsWith(head) }
                .entries.drop(offset).take(limit)
                .map { (name, stored) -> StoreItem(name.removePrefix(head), stored.value, stored.version) }
        }

        override fun delete(namespace: List<String>, key: String) {
            entries.remove(prefix(namespace) + key)
        }

        /** The value and version under a key, or null when nothing is stored there. */
        protected fun slot(namespace: List<String>, key: String): Pair<String, Stored>? = (prefix(namespace) + key).let { name -> entries[name]?.let { name to it } }

        protected fun prefix(namespace: List<String>): String = namespace.joinToString("/", postfix = "/")

        protected fun write(namespace: List<String>, key: String, value: Map<String, Any>, version: Long) {
            entries[prefix(namespace) + key] = Stored(value, version)
        }
    }

    /** Real compare-and-swap including create-if-absent: what a MinIO that honours its own headers gives. */
    private open class HonestStore : NoCasStore() {
        override fun putIfVersion(
            namespace: List<String>,
            key: String,
            value: Map<String, Any>,
            expectedVersion: Long,
        ): Boolean {
            val current = slot(namespace, key)?.second?.version ?: 0L
            if (current != expectedVersion) return false
            write(namespace, key, value, expectedVersion + 1L)
            return true
        }
    }

    /** Accepts the precondition and writes regardless, which is what an unenforcing gateway does. */
    private class LyingStore : NoCasStore() {
        override fun putIfVersion(
            namespace: List<String>,
            key: String,
            value: Map<String, Any>,
            expectedVersion: Long,
        ): Boolean {
            write(namespace, key, value, (slot(namespace, key)?.second?.version ?: 0L) + 1L)
            return true
        }
    }

    private class ThrowingStore : HonestStore() {
        override fun putIfVersion(
            namespace: List<String>,
            key: String,
            value: Map<String, Any>,
            expectedVersion: Long,
        ): Boolean = throw RuntimeException("boom: this backend does not know about If-Match")
    }
}
