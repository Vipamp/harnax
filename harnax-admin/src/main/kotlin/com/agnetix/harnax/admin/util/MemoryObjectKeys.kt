package com.agnetix.harnax.admin.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The object keys long-term memory lives under in the shared store bucket.
 *
 * The agent runtime writes these objects, not admin: `MinioBaseStore` (harnax-harness-core) builds every
 * key as `keyPrefix + namespace.joinToString("/") + "/" + itemKey.removePrefix("/")`, and
 * `MemoryFilesystemRoutes` builds the namespace as
 * `tenants/<tenantId>/users/<userId>/agents/<agentId>/<root|memory>`. Admin only ever reads and removes
 * what that pair produced, so the two rules are reproduced here verbatim rather than re-derived — a key
 * that drifts by one character is a memory file admin cannot see.
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

    /** The item key of the curated layer inside the [ROOT_SEGMENT] route. */
    const val MEMORY_MD_ITEM_KEY = "/MEMORY.md"

    /** The `agents` namespace segment, kept because the runtime keys memory per agent as well as per owner. */
    private const val AGENTS_SEGMENT = "agents"

    /** The two segments a memory object can sit under; anything else in a listing is not memory. */
    private val ROUTE_SEGMENTS = setOf(ROOT_SEGMENT, MEMORY_SEGMENT)

    /**
     * The agent id the runtime uses as a namespace segment is `agentSpec.name`, so it is a free-text
     * column here and a path component there. Letters and digits (any script, since agent names are
     * user-facing and not only ASCII) plus `.`, `_` and `-`, up to the 100 characters the `agent.name`
     * column allows.
     *
     * The set deliberately excludes `/`, `\`, whitespace and every control character, and requires a
     * leading letter or digit so a bare `..` cannot even start. [isValidAgentId] checks the same
     * characters again by hand: this is the boundary that decides whether a path variable can climb out
     * of the caller's own bucket, so it must not depend on one regex reading.
     */
    private val AGENT_ID_PATTERN = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}_.-]{0,99}$")

    /**
     * Whether [agentId] may be used as a namespace segment.
     *
     * Rejected outright, in this order: blank, longer than the column it comes from, a dot-dot sequence
     * (the traversal that survives URL-decoding), a slash or a backslash (a new path level), and any
     * control character including CR/LF (a request line or header split). The allow-pattern is the last
     * word, not the first, so a name that sneaks past the explicit checks still has to consist of the
     * characters an agent name is made of.
     */
    fun isValidAgentId(agentId: String?): Boolean {
        if (agentId.isNullOrBlank()) return false
        if (agentId.length > 100) return false
        if (agentId.contains("..")) return false
        if (agentId.contains('/') || agentId.contains('\\')) return false
        if (agentId.any { it.isISOControl() }) return false
        return AGENT_ID_PATTERN.matches(agentId)
    }

    /**
     * The owner segment memory is bucketed under.
     *
     * The runtime's user identity is `UserIdentifier.userId`, the numeric `sys_user.id`, so the segment is
     * that id as text. This is the one place that choice is made: if the runtime ever keys the bucket with
     * the username instead, only this function moves.
     */
    fun userSegment(userId: Long): String = userId.toString()

    /** The full namespace of one memory route, in the order `MemoryFilesystemRoutes` builds it. */
    fun namespace(
        tenantId: Long,
        userId: String,
        agentId: String,
        segment: String,
    ): List<String> = listOf("tenants", tenantId.toString(), "users", userId, "agents", agentId, segment)

    /** The curated layer's namespace: `tenants/<id>/users/<uid>/agents/<agentId>/root`. */
    fun rootNamespace(
        tenantId: Long,
        userId: String,
        agentId: String,
    ): List<String> = namespace(tenantId, userId, agentId, ROOT_SEGMENT)

    /** The daily ledger's namespace: `tenants/<id>/users/<uid>/agents/<agentId>/memory`. */
    fun memoryNamespace(
        tenantId: Long,
        userId: String,
        agentId: String,
    ): List<String> = namespace(tenantId, userId, agentId, MEMORY_SEGMENT)

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
    ): String = buildKey(keyPrefix, rootNamespace(tenantId, userId, agentId), MEMORY_MD_ITEM_KEY)

    /** One daily ledger key of one agent, e.g. `store/tenants/4/users/7/agents/Research/memory/2026-10-05.md`. */
    fun dailyKey(
        keyPrefix: String,
        tenantId: Long,
        userId: String,
        agentId: String,
        date: String,
    ): String = buildKey(keyPrefix, memoryNamespace(tenantId, userId, agentId), "/$date.md")

    /**
     * The narrowest prefix that still covers everything one owner wrote, used to enumerate their agents.
     * Always ends with `/`, so a listing cannot be widened by a prefix that is a prefix of a name.
     */
    fun ownerPrefix(
        keyPrefix: String,
        tenantId: Long,
        userId: String,
    ): String = "$keyPrefix" + listOf("tenants", tenantId.toString(), "users", userId).joinToString("/") + "/"

    /** Everything one agent wrote, both routes included. */
    fun agentPrefix(
        keyPrefix: String,
        tenantId: Long,
        userId: String,
        agentId: String,
    ): String = "${ownerPrefix(keyPrefix, tenantId, userId)}$AGENTS_SEGMENT/$agentId/"

    /**
     * What one listed object is: which agent's memory, under which route, with which item key.
     *
     * @param agentId the `agents/<id>` segment as the store holds it
     * @param segment [ROOT_SEGMENT] or [MEMORY_SEGMENT]
     * @param itemKey the path below the route, always with a leading `/`
     */
    data class Location(
        val agentId: String,
        val segment: String,
        val itemKey: String,
    )

    /**
     * Decode an object key that was listed under [ownerPrefix].
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
        val segment = parts[2]
        if (agentId.isBlank() || segment !in ROUTE_SEGMENTS) return null
        val itemKey = parts.drop(3).joinToString("/")
        if (itemKey.isBlank()) return null
        return Location(agentId, segment, "/$itemKey")
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
}
