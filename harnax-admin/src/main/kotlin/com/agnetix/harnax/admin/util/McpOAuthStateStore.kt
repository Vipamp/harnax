package com.agnetix.harnax.admin.util

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The authorization request waiting for its code.
 *
 * [userId] and [tenantId] are captured here because the request that starts the flow is authenticated
 * and the one that finishes it is a separate round trip: the browser comes back through a page that
 * carries a JWT of its own, so the two identities have to be compared. A `state` handed to someone
 * else is therefore a dead end - it names its owner, and the exchange refuses anything that does not
 * come back from the account that created it.
 */
class PendingAuthorization internal constructor(
    val tenantId: Long,
    val userId: Long,
    val mcpId: Long,
    val issuer: String,
    val codeVerifier: String,
    val redirectUri: String,
    /** The RFC 8707 `resource` that went out, kept so the token answer can be checked against it. */
    val resource: String?,
    val requestedScopes: List<String>,
    private val expiresAtNanos: Long,
) {
    val expired: Boolean get() = System.nanoTime() > expiresAtNanos
}

/**
 * In-memory store for [PendingAuthorization], one entry per outstanding authorization.
 *
 * Deliberately not a database table. A pending authorization lives for [TTL] and for one use, and it
 * holds a `code_verifier` whose only purpose is to redeem a code that itself lives for minutes;
 * persisting it would put a half-usable credential where a backup could find it, and buying
 * survivability across a restart for a request a user has to redo anyway is not worth that.
 *
 * The consequence to know about: restarting admin while a user sits on the authorization server's
 * consent screen drops their pending state, and the exchange then answers "unknown or expired". The
 * user starts again from the MCP page; nothing stored is harmed.
 */
@Component
class McpOAuthStateStore {

    private val log = LoggerFactory.getLogger(McpOAuthStateStore::class.java)

    private val pending = ConcurrentHashMap<String, PendingAuthorization>()

    /**
     * Records one outstanding authorization.
     *
     * @return false when the store is full, which the caller turns into a refusal: the cap exists
     * because a user with a script can otherwise make this map grow without bound, and an unbounded
     * cache of verifiers is a memory leak with a security label on it.
     */
    fun put(
        state: String,
        authorization: PendingAuthorization,
    ): Boolean {
        sweep()
        if (pending.size >= MAX_PENDING) {
            log.warn("Refusing an OAuth authorization request: {} are already pending", pending.size)
            return false
        }
        pending[state] = authorization
        return true
    }

    /**
     * Takes the entry out, returning null when there is none or it has aged out.
     *
     * Removal happens before the token exchange is attempted, so an exchange that fails cannot be
     * retried with the same `state`, and a concurrent second request with the same `state` loses.
     */
    fun consume(state: String): PendingAuthorization? {
        if (state.isBlank()) {
            return null
        }
        return pending.remove(state)?.takeUnless { it.expired }
    }

    /**
     * How many outstanding authorizations belong to one user, for the caller's own per-user cap.
     *
     * Sweeps first so entries that have aged out do not count against the user any more.
     */
    fun countFor(userId: Long): Int {
        sweep()
        return pending.values.count { it.userId == userId }
    }

    /** Number of entries held, expired ones included until a sweep runs - for tests and for a gauge. */
    fun size(): Int = pending.size

    private fun sweep() {
        pending.entries.removeIf { it.value.expired }
    }

    companion object {
        /**
         * Long enough to survive reading a consent screen and logging in at the authorization server,
         * short enough that an abandoned request is not a live offer for the rest of the day.
         */
        val TTL: Duration = Duration.ofMinutes(5)

        /** Pending authorizations held at once; [put] refuses beyond this. */
        const val MAX_PENDING = 500

        /**
         * Pending authorizations one user may hold at once, checked by the caller through [countFor].
         *
         * [MAX_PENDING] alone is a shared budget, so one authenticated script could fill it and leave
         * every other user unable to start an authorization until the entries age out. This is the
         * part of the budget one user is allowed to take.
         */
        const val MAX_PER_USER = 5

        fun newExpiresAtNanos(): Long = System.nanoTime() + TTL.toNanos()
    }
}
