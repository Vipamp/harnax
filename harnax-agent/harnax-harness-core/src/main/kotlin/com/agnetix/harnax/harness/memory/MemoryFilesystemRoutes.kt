package com.agnetix.harnax.harness.memory

import io.agentscope.harness.agent.filesystem.AbstractFilesystem
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory

/**
 * The memory bucket, mounted as two filesystem routes.
 *
 * The framework's own routes key memory under whatever [io.agentscope.harness.agent.IsolationScope]
 * the deployment picked, and none of its four scopes carries a tenant — `SESSION` gives one memory file
 * per conversation, `AGENT` and `GLOBAL` share memory between users. Mounting `MEMORY.md` and `memory/`
 * separately wins on prefix length inside the composite the agent builds, so those two files move to an
 * owner bucket while every other route keeps its current key.
 *
 * The owner is a constructor argument rather than the [io.agentscope.core.agent.RuntimeContext] of the
 * call, because that value is also the key of the persisted agent state upstream reads it from: threading
 * an identified user through the call to reach this namespace would re-key every existing session. The
 * agent this route is mounted on is already built for exactly one user — `DefaultAgentRunner` drops and
 * rebuilds a cached agent whose owner differs from the request's — so binding the bucket here both keeps
 * the state key where it is and makes a call unable to redirect memory into somebody else's bucket.
 */
object MemoryFilesystemRoutes {

    /** The curated layer, owned by [io.agentscope.harness.agent.memory.MemoryConsolidator]. */
    const val MEMORY_MD_ROUTE = "MEMORY.md"

    /** The append-only daily ledgers, owned by the per-call flush. */
    const val MEMORY_DIR_ROUTE = "memory/"

    /**
     * The route tail an exact-file route gets, matching the framework's own spelling so both layers sit under
     * comparable keys.
     *
     * Public because a caller that addresses the bucket through the store rather than through a route — the
     * promotion pass, which compares versions — needs the same tail to name the same namespace.
     */
    const val ROOT_SEGMENT = "root"

    /**
     * How the store keys the curated layer inside a bucket's [ROOT_SEGMENT] namespace.
     *
     * The routed write paths hand this form down: [io.agentscope.harness.agent.filesystem.CompositeFilesystem]
     * canonicalizes a matched path to a leading slash before it reaches the backend, so the object exists at
     * `/MEMORY.md` whether the flush wrote it as `MEMORY.md` or the model did as `/MEMORY.md`.
     */
    const val CURATED_ITEM_KEY = "/MEMORY.md"

    /** Public because [BucketScopedWatermarkStore] keeps a bucket's progress beside the ledgers it counts. */
    const val MEMORY_SEGMENT = "memory"

    /**
     * The segment a conversation's own layer nests under, inside the agent segment. Public because
     * harnax-admin decodes the same keys and must spell this one identically.
     */
    const val SESSIONS_SEGMENT = "sessions"

    fun routes(
        store: BaseStore,
        tenantId: Long?,
        userId: String,
        agentId: String,
        tenantScoped: Boolean,
    ): Map<String, AbstractFilesystem> = mapOf(
        MEMORY_MD_ROUTE to route(store, tenantId, userId, agentId, tenantScoped, ROOT_SEGMENT),
        MEMORY_DIR_ROUTE to route(store, tenantId, userId, agentId, tenantScoped, MEMORY_SEGMENT),
    )

    /**
     * The conversation's own bucket, which is the two memory routes re-keyed one segment deeper.
     *
     * Nothing else about the pipeline moves: the flush appends to `memory/` and the consolidation pass
     * curates `MEMORY.md` through whichever route answers, so swapping the tail of the namespace is what
     * makes a conversation extract into its own layer instead of the owner's long-term one.
     */
    fun sessionRoutes(
        store: BaseStore,
        tenantId: Long?,
        userId: String,
        agentId: String,
        sessionId: String,
        tenantScoped: Boolean,
    ): Map<String, AbstractFilesystem> = mapOf(
        MEMORY_MD_ROUTE to sessionRoute(store, tenantId, userId, agentId, sessionId, tenantScoped, ROOT_SEGMENT),
        MEMORY_DIR_ROUTE to sessionRoute(store, tenantId, userId, agentId, sessionId, tenantScoped, MEMORY_SEGMENT),
    )

    /**
     * The bucket tuple, one segment pair per owner dimension: `tenants/<id>/users/<uid>/agents/<agentId>`
     * plus this route's own tail. [agentId] is `AgentSpec.name`, the value the runtime keys memory by, and
     * it is kept because the framework writes it into every default route — dropping it would make an
     * agent's memory follow the user across agents, which is a different product decision than the one the
     * bucket made here.
     */
    fun namespace(
        tenantId: Long?,
        userId: String,
        agentId: String,
        tenantScoped: Boolean,
        segment: String,
    ): List<String> = bucketNamespace(tenantId, userId, agentId, null, tenantScoped) + listOf(segment)

    /**
     * The bucket tuple on its own, with no route tail: `tenants/<id>/users/<uid>/agents/<agentId>`, plus
     * `sessions/<sessionId>` when the bucket is a conversation's.
     *
     * Both layers come from here, one with a route segment appended and the other with a progress object
     * beside it, so a deployment that switches the tenant segment off moves every key of both layers at once
     * rather than leaving one keyed by a tenant the other does not read. A null [sessionId] is the owner's
     * long-term bucket.
     */
    fun bucketNamespace(
        tenantId: Long?,
        userId: String,
        agentId: String,
        sessionId: String?,
        tenantScoped: Boolean,
    ): List<String> = buildList {
        if (tenantScoped) {
            // The launcher refuses a tenant-scoped agent with no tenant before it reaches this factory, so
            // a null here is a caller that skipped that check rather than a deployment shape.
            add("tenants")
            add(checkNotNull(tenantId) { "tenantScoped memory needs a tenant to key the bucket on" }.toString())
        }
        add("users")
        add(userId)
        add("agents")
        add(agentId)
        if (sessionId != null) {
            add(SESSIONS_SEGMENT)
            add(sessionId)
        }
    }

    /**
     * The conversation's bucket tuple: the owner tuple, then `sessions/<sessionId>`, then the caller's
     * [segment]. The session pair goes *inside* the agent segment on purpose, so listing or deleting
     * `agents/<agentId>/` takes a conversation's layer along with the long-term one — the two whole-agent
     * reclamation paths need no session-aware branch.
     */
    fun sessionNamespace(
        tenantId: Long?,
        userId: String,
        agentId: String,
        sessionId: String,
        tenantScoped: Boolean,
        segment: String,
    ): List<String> = bucketNamespace(tenantId, userId, agentId, sessionId, tenantScoped) + listOf(segment)

    private fun route(
        store: BaseStore,
        tenantId: Long?,
        userId: String,
        agentId: String,
        tenantScoped: Boolean,
        segment: String,
    ): AbstractFilesystem = RemoteFilesystem(
        store,
        NamespaceFactory {
            namespace(tenantId, userId, agentId, tenantScoped, segment)
        },
    )

    private fun sessionRoute(
        store: BaseStore,
        tenantId: Long?,
        userId: String,
        agentId: String,
        sessionId: String,
        tenantScoped: Boolean,
        segment: String,
    ): AbstractFilesystem = RemoteFilesystem(
        store,
        NamespaceFactory {
            sessionNamespace(tenantId, userId, agentId, sessionId, tenantScoped, segment)
        },
    )
}
