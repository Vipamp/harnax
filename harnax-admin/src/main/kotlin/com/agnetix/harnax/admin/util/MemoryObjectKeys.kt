package com.agnetix.harnax.admin.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The object keys long-term memory lives under in the shared store bucket.
 *
 * The agent runtime writes these objects, not admin: `MinioBaseStore` (harnax-harness-core) builds every
 * key as `keyPrefix + namespace.joinToString("/") + "/" + itemKey.removePrefix("/")`, and
 * `MemoryFilesystemRoutes` builds the namespace as
 * `[tenants/<tenantId>/]users/<userId>/agents/<agentId>/<root|memory>` — the tenant pair only while its
 * `harness.memory.tenant-scoped` is on. A conversation's own layer is the same shape with the session pair
 * inside the agent segment,
 * `[tenants/<tenantId>/]users/<userId>/agents/<agentId>/sessions/<sessionId>/<root|memory>`, so the decoder
 * below names which layer each listed object belongs to. Admin only ever reads and removes what that pair
 * produced, so the two rules are reproduced here verbatim rather than re-derived — a key that drifts by one
 * character is a memory file admin cannot see.
 *
 * Everything in this object is pure string work with no MinIO in sight, which is what makes the exact
 * key strings assertable in a unit test.
 */
object MemoryObjectKeys {

    /** The global prefix `MinioBaseStore` puts in front of every store object; matches `harness.minio.store-prefix`. */
    const val DEFAULT_KEY_PREFIX = "store/"

    /** The route tail the runtime mounts `MEMORY.md` under. */
    const val ROOT_SEGMENT = "root"

    /** The route tail the runtime appends the daily ledgers under. */
    const val MEMORY_SEGMENT = "memory"

    /** The segment a conversation's own layer nests under, inside the agent segment. */
    const val SESSIONS_SEGMENT = "sessions"

    /** The item key of the curated layer inside the [ROOT_SEGMENT] route. */
    const val MEMORY_MD_ITEM_KEY = "/MEMORY.md"

    /** The `agents` namespace segment, kept because the runtime keys memory per agent as well as per owner. */
    private const val AGENTS_SEGMENT = "agents"

    /** The two segments a memory object can sit under; anything else in a listing is not memory. */
    private val ROUTE_SEGMENTS = setOf(ROOT_SEGMENT, MEMORY_SEGMENT)

    /**
     * Whether [agentId] may be used as a namespace segment.
     *
     * `agent.name` is a free `varchar(100)` — the create form puts no pattern on it — so the runtime writes
     * whatever the owner typed, and an allow-list of "characters an agent name is made of" would silently
     * hide that agent's memory from the page and refuse to delete it. This is therefore a deny-list of the
     * shapes that cannot survive as one path segment, in this order: blank, longer than the column it comes
     * from, a dot-dot sequence (the traversal that survives URL-decoding) or a bare dot segment, a slash or
     * a backslash (a new path level), any control character including CR/LF (a request line or header split),
     * and leading or trailing whitespace (which a key keeps but a form field and a log line do not).
     *
     * Everything else is addressable, including spaces, brackets and CJK punctuation.
     */
    fun isValidAgentId(agentId: String?): Boolean {
        if (agentId.isNullOrBlank()) return false
        if (agentId.length > 100) return false
        if (agentId.contains("..")) return false
        if (agentId == ".") return false
        if (agentId.contains('/') || agentId.contains('\\')) return false
        if (agentId.any { it.isISOControl() }) return false
        return !agentId.first().isWhitespace() && !agentId.last().isWhitespace()
    }

    /**
     * The owner segment memory is bucketed under.
     *
     * The runtime's user identity is `UserIdentifier.userId`, the numeric `sys_user.id`, so the segment is
     * that id as text. This is the one place that choice is made: if the runtime ever keys the bucket with
     * the username instead, only this function moves.
     */
    fun userSegment(userId: Long): String = userId.toString()

    /**
     * The full namespace of one memory route, in the order `MemoryFilesystemRoutes` builds it.
     *
     * [tenantScoped] is that writer's `harness.memory.tenant-scoped`: off means the runtime leaves the tenant
     * out of the key entirely, so one owner's memory sits under `users/<uid>` for every workspace they
     * belong to. [tenantId] is then not part of the answer, exactly as it is not part of the write.
     */
    fun namespace(
        tenantId: Long,
        userId: String,
        agentId: String,
        segment: String,
        tenantScoped: Boolean = true,
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

    /** The curated layer's namespace: `tenants/<id>/users/<uid>/agents/<agentId>/root`. */
    fun rootNamespace(
        tenantId: Long,
        userId: String,
        agentId: String,
        tenantScoped: Boolean = true,
    ): List<String> = namespace(tenantId, userId, agentId, ROOT_SEGMENT, tenantScoped)

    /** The daily ledger's namespace: `tenants/<id>/users/<uid>/agents/<agentId>/memory`. */
    fun memoryNamespace(
        tenantId: Long,
        userId: String,
        agentId: String,
        tenantScoped: Boolean = true,
    ): List<String> = namespace(tenantId, userId, agentId, MEMORY_SEGMENT, tenantScoped)

    /**
     * One object's key, exactly as `MinioBaseStore.buildKey` writes it: prefix, namespace joined by `/`,
     * then the item key without its leading slash.
     */
    fun buildKey(
        keyPrefix: String,
        namespace: List<String>,
        itemKey: String,
    ): String {
        val nsPart = namespace.joinToString("/")
        val cleanKey = itemKey.removePrefix("/")
        return if (nsPart.isEmpty()) {
            "$keyPrefix$cleanKey"
        } else {
            "$keyPrefix$nsPart/$cleanKey"
        }
    }

    /** The curated `MEMORY.md` key of one agent. */
    fun memoryMdKey(
        keyPrefix: String,
        tenantId: Long,
        userId: String,
        agentId: String,
        tenantScoped: Boolean = true,
    ): String = buildKey(keyPrefix, rootNamespace(tenantId, userId, agentId, tenantScoped), MEMORY_MD_ITEM_KEY)

    /** One daily ledger key of one agent, e.g. `store/tenants/4/users/7/agents/Research/memory/2026-10-05.md`. */
    fun dailyKey(
        keyPrefix: String,
        tenantId: Long,
        userId: String,
        agentId: String,
        date: String,
        tenantScoped: Boolean = true,
    ): String = buildKey(keyPrefix, memoryNamespace(tenantId, userId, agentId, tenantScoped), "/$date.md")

    /**
     * One conversation's curated file: the owner tuple, then `sessions/<sid>`, then the `root` route tail.
     *
     * The session pair sits inside the agent prefix, so this key is covered by both [agentPrefix] and
     * [ownerPrefix] and therefore by both whole-agent and whole-user deletes without a session-aware branch.
     */
    fun sessionMemoryMdKey(
        keyPrefix: String,
        tenantId: Long,
        userId: String,
        agentId: String,
        sessionId: String,
        tenantScoped: Boolean = true,
    ): String = buildKey(
        keyPrefix,
        namespace(tenantId, userId, agentId, SESSIONS_SEGMENT, tenantScoped) + listOf(sessionId, ROOT_SEGMENT),
        MEMORY_MD_ITEM_KEY,
    )

    /**
     * The narrowest prefix that still covers everything one owner wrote, used to enumerate their agents.
     * Always ends with `/`, so a listing cannot be widened by a prefix that is a prefix of a name.
     */
    fun ownerPrefix(
        keyPrefix: String,
        tenantId: Long,
        userId: String,
        tenantScoped: Boolean = true,
    ): String {
        val parts = if (tenantScoped) {
            listOf("tenants", tenantId.toString(), "users", userId)
        } else {
            listOf("users", userId)
        }
        return "$keyPrefix${parts.joinToString("/")}/"
    }

    /** Everything one agent wrote, both routes included. */
    fun agentPrefix(
        keyPrefix: String,
        tenantId: Long,
        userId: String,
        agentId: String,
        tenantScoped: Boolean = true,
    ): String = "${ownerPrefix(keyPrefix, tenantId, userId, tenantScoped)}$AGENTS_SEGMENT/$agentId/"

    /**
     * What one listed object is: which agent's memory, under which route, with which item key.
     *
     * @param agentId the `agents/<id>` segment as the store holds it
     * @param segment [ROOT_SEGMENT] or [MEMORY_SEGMENT]
     * @param itemKey the path below the route, always with a leading `/`
     * @param sessionId the `sessions/<sid>` segment when the object is a conversation's own layer, and null
     *   when it is the long-term layer this owner shares across conversations. The page shows only the null
     *   rows; both layers go together when an agent or a user is deleted.
     */
    data class Location(
        val agentId: String,
        val segment: String,
        val itemKey: String,
        val sessionId: String? = null,
    )

    /**
     * Decode an object key that was listed under [ownerPrefix].
     *
     * Two shapes come out of the runtime: the long-term layer at `agents/<id>/<root|memory>/…` and a
     * conversation's own layer at `agents/<id>/sessions/<sid>/<root|memory>/…`. Both decode here, because
     * both have to be deletable by the sweeps that address an agent or a user — a session-nested key this
     * function refused would be left in the bucket after its owner was deleted.
     *
     * Returns null for anything that is not a memory object of this owner — a foreign key, an agent whose
     * own name contains a slash (which would shift the route segment out of place), or a directory marker.
     * A null is a key admin must not touch through the agent-scoped endpoints, so refusing to name an
     * agent here is the safe answer, not a best guess.
     */
    fun locationOf(
        ownerPrefix: String,
        objectKey: String,
    ): Location? {
        if (!objectKey.startsWith(ownerPrefix)) return null
        val parts = objectKey.removePrefix(ownerPrefix).trimStart('/').split("/")
        if (parts.size < 3 || parts[0] != AGENTS_SEGMENT) return null
        val agentId = parts[1]
        var index = 2
        val sessionId = if (parts.getOrNull(index) == SESSIONS_SEGMENT) {
            val sid = parts.getOrNull(index + 1)
            if (sid.isNullOrBlank()) return null
            index += 2
            sid
        } else {
            null
        }
        if (index >= parts.size) return null
        val segment = parts[index]
        if (agentId.isBlank() || segment !in ROUTE_SEGMENTS) return null
        val itemKey = parts.drop(index + 1).joinToString("/")
        if (itemKey.isBlank()) return null
        return Location(agentId, segment, "/$itemKey", sessionId)
    }

    /** The `YYYY-MM-DD` part of a daily ledger key, or null when the item key is not a dated markdown file. */
    fun dateOf(itemKey: String): String? {
        val name = itemKey.removePrefix("/")
        if (!name.endsWith(".md")) return null
        val date = name.removeSuffix(".md")
        // Parsed rather than shape-checked: a key the runtime did not write (a day that does not exist) is
        // not a ledger entry, and the page would offer a date no conversation ever happened on.
        return try {
            LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE)
            date
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Whether one conversation's object is memory its owner has not been told yet.
     *
     * The two shapes the merge reads, and nothing else: the conversation's own `MEMORY.md` draft and its
     * dated ledgers. The rest of that bucket is bookkeeping the passes leave behind — the consolidation
     * progress object, and the days upstream retired into `archive/`, which are history the long-term layer
     * already curates and which the merge (harnax-harness-core's `MemoryPromoter`) deliberately neither
     * re-promotes nor deletes. Counting either would leave an agent whose every conversation has been merged
     * still owing its owner a pending merge, forever.
     */
    fun hasUnmergedContent(location: Location): Boolean = when (location.segment) {
        ROOT_SEGMENT -> location.itemKey == MEMORY_MD_ITEM_KEY
        MEMORY_SEGMENT -> dateOf(location.itemKey) != null
        else -> false
    }
}
