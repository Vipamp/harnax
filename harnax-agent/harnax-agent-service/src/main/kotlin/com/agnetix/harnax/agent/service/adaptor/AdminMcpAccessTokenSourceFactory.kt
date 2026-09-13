package com.agnetix.harnax.agent.service.adaptor

import com.agnetix.harnax.agent.adaptor.McpAccessTokenSourceFactory
import com.agnetix.harnax.agent.adaptor.mcp.McpAccessTokenSource
import com.agnetix.harnax.agent.adaptor.mcp.McpAuthRequiredException
import com.agnetix.harnax.agent.service.client.AdminApiClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Gets OAuth MCP tokens from admin, one identity per agent instance.
 *
 * The token belongs to whoever owns the session, and this is where that person is captured: the
 * source returned by [forUser] can only ever ask for the token of the session it was built for. An
 * MCP client is created with the agent and then reused for every call that agent serves, so a source
 * that looked up "the current user" per request would hand user A's token to user B as soon as a
 * cached agent was shared (design section 7.3).
 *
 * [AdminApiClient] is asked with the session id and nothing else. Admin resolves the owner itself,
 * which also means the `userId` here is only a presence check - it never travels over the wire, so a
 * wrong or missing value cannot make admin issue someone else's token.
 */
@Component
class AdminMcpAccessTokenSourceFactory(
    private val adminApiClient: AdminApiClient,
) : McpAccessTokenSourceFactory {

    private val log = LoggerFactory.getLogger(AdminMcpAccessTokenSourceFactory::class.java)

    /** session + server -> the token admin minted for it, until it ages out. */
    private val cache = ConcurrentHashMap<CacheKey, CachedToken>()

    /**
     * session + server -> the last refusal, until it is worth asking again.
     *
     * A missing grant is fixed by a person clicking authorize, not by retrying, and without this each
     * tool call would be another admin round trip, another refresh attempt against the authorization
     * server, and another `mcp_call_log` row saying the same thing.
     */
    private val refusals = ConcurrentHashMap<CacheKey, Refusal>()

    /**
     * Fixed stripes instead of a lock per key: two threads minting the same token at once would make
     * admin refresh it twice, and a rotated refresh token makes the second attempt fail. A stripe can
     * serialize unrelated sessions for one round trip, which beats an unbounded lock map.
     */
    private val locks = Array(LOCK_STRIPES) { ReentrantLock() }

    override fun forUser(
        sessionId: String,
        userId: Long?,
    ): McpAccessTokenSource? {
        if (userId == null) {
            // A channel conversation or a service key has no grant to spend, so there is no source to
            // hand out and the caller leaves the OAuth server out of the toolkit.
            return null
        }
        return McpAccessTokenSource { mcpId -> accessToken(sessionId, mcpId) }
    }

    private fun accessToken(sessionId: String, mcpId: Long): String {
        val key = CacheKey(sessionId, mcpId)
        cachedValue(key)?.let { return it }
        refusedRecently(key)?.let { throw McpAuthRequiredException(it) }
        val lock = locks[(key.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES]
        lock.lock()
        try {
            // Re-read: another thread may have minted it (or been refused) while this one waited.
            cachedValue(key)?.let { return it }
            refusedRecently(key)?.let { throw McpAuthRequiredException(it) }
            val issued = try {
                adminApiClient.getMcpAccessToken(sessionId, mcpId)
            } catch (e: McpAuthRequiredException) {
                // Whatever was cached is now known to be unwanted; keeping it would re-attach a token
                // admin has already marked as needing consent.
                cache.remove(key)
                keepRoom()
                refusals[key] = Refusal(e.message ?: "MCP authorization is required", REFUSAL_COOLDOWN_SECONDS)
                throw e
            }
            keepRoom()
            refusals.remove(key)
            cache[key] = CachedToken(issued.accessToken, issued.expiresAtEpochSecond)
            log.debug("Cached MCP access token for session={}, mcpId={}", sessionId, mcpId)
            return issued.accessToken
        } finally {
            lock.unlock()
        }
    }

    private fun cachedValue(key: CacheKey): String? = cache[key]
        ?.takeIf { it.usable() }
        ?.accessToken

    /** The message admin gave for this session and server, while the cooldown still stands. */
    private fun refusedRecently(key: CacheKey): String? = refusals[key]
        ?.takeUnless { it.expired() }
        ?.message
        ?.also { log.debug("MCP token for session={}, mcpId={} is in its refusal cooldown", key.sessionId, key.mcpId) }

    /**
     * Bound both maps: drop what has aged out, then as many arbitrary entries as it takes.
     *
     * Emptying the map is not an option even though it is the simplest bound - every session that lost
     * a live token would then mint a new one from inside the MCP request customizer, on whatever thread
     * the client sends from, so one full cache would become a stampede of blocking round trips.
     */
    private fun keepRoom() {
        if (cache.size < MAX_ENTRIES && refusals.size < MAX_ENTRIES) {
            return
        }
        cache.entries.removeAll { !it.value.usable() }
        refusals.entries.removeAll { it.value.expired() }
        dropToFit(cache, cache.size - FILL_TARGET)
        dropToFit(refusals, refusals.size - FILL_TARGET)
    }

    private fun <V> dropToFit(map: ConcurrentHashMap<CacheKey, V>, howMany: Int) {
        if (howMany <= 0) {
            return
        }
        val iterator = map.keys.iterator()
        repeat(howMany) {
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    /**
     * Who a token was minted for, which is why both parts are in the key: the same MCP server reached
     * from two sessions presents two different tokens.
     */
    private data class CacheKey(val sessionId: String, val mcpId: Long)

    private class CachedToken(val accessToken: String, val expiresAtEpochSecond: Long?) {

        /** Horizon for a token whose issuer never stated one, so the lease still ends. */
        private val fallbackHorizon: Long = Instant.now().epochSecond + FALLBACK_TTL_SECONDS

        fun usable(): Boolean = Instant.now().epochSecond + REFRESH_SKEW_SECONDS <
            (expiresAtEpochSecond ?: fallbackHorizon)

        override fun toString(): String = "CachedToken(expiresAtEpochSecond=$expiresAtEpochSecond)"
    }

    /** Why admin said no, and until when it is not worth asking again. */
    private class Refusal(val message: String, cooldownSeconds: Long) {

        private val horizon: Long = Instant.now().epochSecond + cooldownSeconds

        fun expired(): Boolean = Instant.now().epochSecond >= horizon
    }

    companion object {
        private const val LOCK_STRIPES = 64
        private const val MAX_ENTRIES = 2048

        /** How far down `keepRoom` evicts, so the next fills have headroom before another sweep. */
        private const val FILL_TARGET = MAX_ENTRIES / 2

        /** Renew this many seconds before the stated expiry, so a long call doesn't cross it. */
        private const val REFRESH_SKEW_SECONDS = 30L

        /** Lease given to a token with no expiry, after which admin is asked again. */
        private const val FALLBACK_TTL_SECONDS = 300L

        /**
         * How long a "go authorize this" answer is held. Short: a user who just authorized in another
         * tab should be working again on the next call, and this only has to collapse the burst of
         * calls that one failed tool makes.
         */
        private const val REFUSAL_COOLDOWN_SECONDS = 15L
    }
}
