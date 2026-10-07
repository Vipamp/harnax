package com.agnetix.harnax.harness.memory

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore

/**
 * A memory bucket bound to one owner, resolved once and read from several places.
 *
 * The bucket's identity has to be a value rather than an argument list at each mount point because several
 * things key off it and only one of them is a route: the two memory routes of whichever layer a delivery
 * mounts, the consolidation progress upstream keeps in a namespace of its own that carries no owner at all —
 * which [BucketScopedWatermarkStore] relocates into this bucket — and the owner's curated layer, injected
 * beside a conversation's own. All of them need the same tuple, so all of them come from here, and the set
 * cannot drift into a progress that counts one bucket's ledgers and a bucket the routes never write.
 */
class MemoryDomain(
    val store: BaseStore,
    val tenantId: Long?,
    val owner: String,
    val agentId: String,
    val tenantScoped: Boolean,
) {

    /** The owner's long-term bucket: the curated layer and the daily ledgers, one route each. */
    fun routes(): Map<String, AbstractFilesystem> = MemoryFilesystemRoutes.routes(store, tenantId, owner, agentId, tenantScoped)

    /**
     * One conversation's own bucket, mounted at the same two route prefixes as the long-term one.
     *
     * The prefixes cannot be shared, and they do not need to be: the flush, the consolidation pass and the
     * four memory tools all address `MEMORY.md` and `memory/`, so whichever bucket answers there is the layer
     * this conversation extracts into and archives by hand (design 11.1). The one that has to stay off those
     * prefixes is the long-term layer, which nothing but the promoter writes.
     */
    fun routes(sessionId: String): Map<String, AbstractFilesystem> = MemoryFilesystemRoutes.sessionRoutes(store, tenantId, owner, agentId, sessionId, tenantScoped)

    /**
     * The owner's curated long-term memory, or null when nothing is curated yet.
     *
     * Read through the same route class that writes the bucket rather than straight off [store], because the
     * item's payload shape is that class's business — a legacy object or an unexpected encoding decodes here
     * exactly as it does for the routes themselves. One route object is built for the domain instead of per
     * call: the read happens on the conversation path, once per model call.
     */
    fun longTermCurated(): String? {
        val result = longTermCuratedRoute.read(RuntimeContext.empty(), MemoryFilesystemRoutes.CURATED_ITEM_KEY, 0, 0)
        return if (result.isSuccess) result.fileData()?.content() else null
    }

    /**
     * The namespace one bucket's curated file lives in: its [namespace] plus the `root` route tail.
     *
     * The promotion pass reads and compare-and-swaps this object through the store rather than through a
     * route, because a route hands back content and nothing else while the write it has to make carries a
     * version precondition.
     */
    fun curatedNamespace(sessionId: String?): List<String> = namespace(sessionId) + MemoryFilesystemRoutes.ROOT_SEGMENT

    /** The namespace one bucket's daily ledgers live in: its [namespace] plus the `memory` route tail. */
    fun ledgerNamespace(sessionId: String?): List<String> = namespace(sessionId) + MemoryFilesystemRoutes.MEMORY_SEGMENT

    /**
     * The bucket tuple with no route tail: `tenants/<id>/users/<uid>/agents/<agentId>`, plus
     * `sessions/<sid>` for a conversation's own bucket. A null [sessionId] is the owner's long-term bucket,
     * which is the one the consolidation progress belongs to.
     */
    fun namespace(sessionId: String?): List<String> = MemoryFilesystemRoutes.bucketNamespace(tenantId, owner, agentId, sessionId, tenantScoped)

    private val longTermCuratedRoute by lazy { routes().getValue(MemoryFilesystemRoutes.MEMORY_MD_ROUTE) }
}
