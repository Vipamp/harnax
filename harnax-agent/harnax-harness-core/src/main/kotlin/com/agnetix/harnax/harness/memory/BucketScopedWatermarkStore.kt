package com.agnetix.harnax.harness.memory

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem

/**
 * A [BaseStore] that keeps the consolidation progress inside one memory bucket, and passes everything else
 * to [delegate] untouched.
 *
 * `MemoryConsolidator` remembers how far it has merged an owner's daily ledgers by writing one object at
 * `memory/consolidation` in the shared store. That address carries no owner, so on a deployment with more
 * than one user every consolidation of every agent advances the same number — and the number decides which
 * ledgers count as already handled. The entries of an owner whose turn has not come yet are skipped without
 * an error and without a log line, which is what "the memory stopped growing and nothing failed" looks like.
 * Layering the bucket makes the same object wrong a second way: a conversation that consolidates would mark
 * its owner's cross-session memory as done.
 *
 * So exactly one address is moved, in the shape [com.agnetix.harnax.harness.minio.ProcessLocalCoordinationStore]
 * already uses for the throttle: the pipeline, its prompt and its file layout do not change, and a store that
 * cannot answer for a bucket only ever answers for that bucket.
 *
 * The progress goes in the bucket's own ledger namespace rather than in a namespace of its own, because two
 * readers already look there and neither is harmed: [MemoryConsolidator]-style globs take `*.md`, and
 * harnax-admin's memory page shows only the items whose name parses as a day. What that placement buys is
 * reclamation — an agent's memory and a user's memory are deleted by listing their prefix, and a progress
 * object outside that prefix would outlive the ledgers it describes, silently suppressing consolidation for
 * whoever next got that bucket key.
 *
 * [bucket] is the owner tuple of the assembly this store belongs to, in the order
 * [MemoryFilesystemRoutes.namespace] builds it and with no route tail on it: `tenants/<t>/users/<u>/agents/<a>`
 * for the long-term layer, that plus `sessions/<sid>` for a conversation. It is fixed here rather than read
 * off the call for the same reason the routes fix it: the identity that owns a bucket is the one the agent
 * was assembled for.
 */
class BucketScopedWatermarkStore(
    private val delegate: BaseStore,
    bucket: List<String>,
) : BaseStore {

    /** Where the progress of this bucket lives: beside the daily ledgers it counts. */
    private val watermarkNamespace = bucket + listOf(MemoryFilesystemRoutes.MEMORY_SEGMENT)

    override fun get(
        namespace: List<String>,
        key: String,
    ): StoreItem? = delegate.get(addressOf(namespace), key)

    override fun put(
        namespace: List<String>,
        key: String,
        value: Map<String, Any>,
    ) = delegate.put(addressOf(namespace), key, value)

    override fun putIfVersion(
        namespace: List<String>,
        key: String,
        value: Map<String, Any>,
        expectedVersion: Long,
    ): Boolean = delegate.putIfVersion(addressOf(namespace), key, value, expectedVersion)

    override fun search(
        namespace: List<String>,
        limit: Int,
        offset: Int,
    ): List<StoreItem> = delegate.search(addressOf(namespace), limit, offset)

    override fun delete(
        namespace: List<String>,
        key: String,
    ) = delegate.delete(addressOf(namespace), key)

    /** The one address this store answers for itself; every other namespace is the caller's own business. */
    private fun addressOf(namespace: List<String>): List<String> = if (namespace == UPSTREAM_NAMESPACE) watermarkNamespace else namespace

    companion object {

        /**
         * Where upstream puts the progress, verbatim: `MemoryConsolidator.WATERMARK_NAMESPACE`, a private
         * constant. [BucketScopedWatermarkStoreTest] reads that field back, so moving it here without moving
         * it there fails loudly instead of quietly handing every bucket the shared address again.
         */
        val UPSTREAM_NAMESPACE: List<String> = listOf("memory", "consolidation")

        /** The item name upstream writes under it, and the name this store keeps. */
        const val UPSTREAM_KEY: String = "watermark"
    }
}
