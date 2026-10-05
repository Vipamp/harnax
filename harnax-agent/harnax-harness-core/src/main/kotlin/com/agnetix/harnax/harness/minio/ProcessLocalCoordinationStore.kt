package com.agnetix.harnax.harness.minio

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import java.util.concurrent.ConcurrentHashMap

/**
 * A [BaseStore] that answers the coordination namespace in this process and forwards everything else to
 * [delegate], installed when [StoreCasProbe] says the remote store cannot be trusted to compare versions.
 *
 * Upstream picks the consolidation gate from the distributed store alone
 * (`HarnessAgent.java:2409-2412`) with no builder hook, so wrapping the store is the only way to give a
 * deployment a working throttle when its storage will not give one. Intercepting a single namespace is
 * what keeps the cost contained: the memory bucket itself stays remote, so only the deduplication of
 * background consolidation degrades — a claim then means "not in this replica yet" instead of "not
 * anywhere yet", and a fleet of N replicas consolidates N times instead of once. That is the same shape
 * upstream's own `LocalPeriodicGate` fallback has, and it is strictly better than the store-backed gate
 * on a broken store, which either claims nothing (`putIfVersion` defaults to false) or claims everything
 * (a gateway that ignores `If-Match`) and lets every replica race the same write.
 */
class ProcessLocalCoordinationStore(
    private val delegate: BaseStore,
) : BaseStore {

    private data class Slot(val value: Map<String, Any>, val version: Long)

    override fun get(namespace: List<String>, key: String): StoreItem? = if (isCoordination(namespace)) {
        SLOTS[key]?.let { StoreItem(key, it.value, it.version) }
    } else {
        delegate.get(namespace, key)
    }

    override fun put(namespace: List<String>, key: String, value: Map<String, Any>) {
        if (isCoordination(namespace)) {
            SLOTS.compute(key) { _, existing -> Slot(value.toMap(), (existing?.version ?: 0L) + 1L) }
        } else {
            delegate.put(namespace, key, value)
        }
    }

    override fun putIfVersion(
        namespace: List<String>,
        key: String,
        value: Map<String, Any>,
        expectedVersion: Long,
    ): Boolean {
        if (!isCoordination(namespace)) return delegate.putIfVersion(namespace, key, value, expectedVersion)
        // Absent means version 0, which is what makes the gate's create-if-absent claim work: the same
        // reading MinIO's `If-None-Match: *` gives, and the only way a first claim of a fresh slot wins.
        // Compare and write inside one compute, or two threads in this replica both take the same slot.
        var claimed = false
        SLOTS.compute(key) { _, existing ->
            val current = existing?.version ?: 0L
            if (current != expectedVersion) {
                existing
            } else {
                claimed = true
                Slot(value.toMap(), current + 1L)
            }
        }
        return claimed
    }

    override fun search(namespace: List<String>, limit: Int, offset: Int): List<StoreItem> = if (isCoordination(namespace)) {
        SLOTS.entries.drop(offset).take(limit).map { (key, slot) -> StoreItem(key, slot.value, slot.version) }
    } else {
        delegate.search(namespace, limit, offset)
    }

    override fun delete(namespace: List<String>, key: String) {
        if (isCoordination(namespace)) {
            SLOTS.remove(key)
        } else {
            delegate.delete(namespace, key)
        }
    }

    private fun isCoordination(namespace: List<String>): Boolean = namespace == StoreCasProbe.COORDINATION

    companion object {
        /**
         * One map for the whole process, keyed by the slot names upstream builds
         * (`memory-flush:<scope>:<id>` / `memory-maintenance:<scope>:<id>`), so every agent instance in
         * this replica is throttled together. The identity in each key is what keeps two sessions from
         * sharing a slot; entries are a name and a timestamp and are overwritten, never accumulated per
         * call. Mirrors `LocalPeriodicGate.SHARED_LAST_CLAIM_AT`, which is static for the same reason.
         */
        private val SLOTS = ConcurrentHashMap<String, Slot>()
    }
}
