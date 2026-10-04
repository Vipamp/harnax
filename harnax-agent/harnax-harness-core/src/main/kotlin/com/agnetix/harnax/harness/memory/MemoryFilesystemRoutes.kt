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

    /** Matches the framework's own segment for an exact-file route, so both layers sit under comparable keys. */
    private const val ROOT_SEGMENT = "root"
    private const val MEMORY_SEGMENT = "memory"

    fun routes(
        store: BaseStore,
        tenantId: Long,
        userId: String,
        agentId: String,
        tenantScoped: Boolean,
    ): Map<String, AbstractFilesystem> = mapOf(
        MEMORY_MD_ROUTE to route(store, tenantId, userId, agentId, tenantScoped, ROOT_SEGMENT),
        MEMORY_DIR_ROUTE to route(store, tenantId, userId, agentId, tenantScoped, MEMORY_SEGMENT),
    )

    /**
     * The bucket tuple, one segment pair per owner dimension: `tenants/<id>/users/<uid>/agents/<agentId>`
     * plus this route's own tail. `agents/<agentId>` is kept because the framework writes it into every
     * default route — dropping it would make an agent's memory follow the user across agents, which is a
     * different product decision than the one the bucket made here.
     */
    fun namespace(
        tenantId: Long,
        userId: String,
        agentId: String,
        tenantScoped: Boolean,
        segment: String,
    ): List<String> = buildList {
        if (tenantScoped) {
            add("tenants")
            add(tenantId.toString())
        }
        add("users")
        add(userId)
        add("agents")
        add(agentId)
        add(segment)
    }

    private fun route(
        store: BaseStore,
        tenantId: Long,
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
}
