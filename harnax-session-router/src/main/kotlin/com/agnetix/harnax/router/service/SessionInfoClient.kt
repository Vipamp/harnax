package com.agnetix.harnax.router.service

import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Caching layer on top of [AdminClientService.lookupSession].
 *
 * Delegates all HTTP calls to the shared [AdminClientService] and adds a
 * local Caffeine cache (5 min TTL) to avoid hitting the Admin service
 * on every request.
 *
 * Only an *answer* is worth remembering. The sentinel this class used to cache covered both "admin
 * says there is no such session" and "admin did not answer", so one admin blip left every session it
 * touched reading as non-existent for five minutes after recovery. An [AdminClientService.SessionLookup.Unreachable]
 * is therefore invalidated straight away and the next request asks again.
 *
 * `Unknown` is still cached, and that carries a known cost worth stating rather than discovering: it is
 * what [SessionAccessGuard] passes. An id admin answers from the `channel` table and from nowhere else —
 * a `chn-` session, whose owning tenant admin learned to report in this release — that was asked about
 * while the answer was still Unknown keeps passing the guard for up to five minutes after the rollout.
 * The window is bounded, needs the caller to already hold that exact UUID, and closing it means either
 * not caching misses (one HTTP call to admin per proxy request, per session) or a cache invalidation
 * broadcast that no deployment here has; it is left open on purpose.
 */
@Component
class SessionInfoClient(
    private val adminClientService: AdminClientService,
    @Value("\${admin.internal-api.timeout-response-ms:3000}")
    private val responseTimeoutMs: Long,
) {

    private val log = LoggerFactory.getLogger(SessionInfoClient::class.java)

    private val cache = Caffeine.newBuilder()
        .maximumSize(5000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build<String, AdminClientService.SessionLookup>()

    /**
     * Loading through [Caffeine.get] rather than get-then-put keeps the per-key single flight: N
     * concurrent requests for one uncached session used to become N calls to admin.
     */
    fun lookup(sessionId: String): AdminClientService.SessionLookup {
        val outcome = cache.get(sessionId) { sid -> load(sid) }
        if (outcome === AdminClientService.SessionLookup.Unreachable) {
            cache.invalidate(sessionId)
        }
        return outcome
    }

    fun getSessionInfo(sessionId: String): AdminClientService.SessionInfo? = (lookup(sessionId) as? AdminClientService.SessionLookup.Found)?.info

    private fun load(sessionId: String): AdminClientService.SessionLookup {
        // Overall request budget: slightly above responseTimeoutMs to leave scheduler slack.
        val overallTimeoutMs = responseTimeoutMs + 1000
        return try {
            runBlocking {
                withTimeoutOrNull(overallTimeoutMs) { adminClientService.lookupSession(sessionId) }
            } ?: AdminClientService.SessionLookup.Unreachable
        } catch (e: Exception) {
            log.warn("[Router→Admin] Failed to get session info for $sessionId: ${e.message}")
            AdminClientService.SessionLookup.Unreachable
        }
    }
}
