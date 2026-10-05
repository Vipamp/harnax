package com.agnetix.harnax.harness.memory

import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore

/**
 * A memory bucket bound to one owner, resolved once and read from two places.
 *
 * The bucket's identity has to be a value rather than an argument list at each mount point because two
 * things key off it and only one of them is a route: the two memory routes below, and the consolidation
 * progress upstream keeps in a namespace of its own that carries no owner at all — which
 * [BucketScopedWatermarkStore] relocates into this bucket. Both need the same tuple, so both come from
 * here, and the pair cannot drift into a progress that counts one bucket's ledgers and a bucket the
 * routes never write.
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
     * The bucket tuple with no route tail: `tenants/<id>/users/<uid>/agents/<agentId>`, plus
     * `sessions/<sid>` for a conversation's own bucket. A null [sessionId] is the owner's long-term bucket,
     * which is the one the consolidation progress belongs to.
     */
    fun namespace(sessionId: String?): List<String> = MemoryFilesystemRoutes.bucketNamespace(tenantId, owner, agentId, sessionId, tenantScoped)
}
