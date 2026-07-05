package com.agnetix.harnax.router.service

import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * Caching layer on top of [AdminClientService.getSessionInfo].
 *
 * Delegates all HTTP calls to the shared [AdminClientService] and adds a
 * local Caffeine cache (5 min TTL) to avoid hitting the Admin service
 * on every request.
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
        .build<String, AdminClientService.SessionInfo?>()

    fun getSessionInfo(sessionId: String): AdminClientService.SessionInfo? = cache.get(sessionId) { sid ->
        // Overall request budget: slightly above responseTimeoutMs to leave scheduler slack.
        val overallTimeoutMs = responseTimeoutMs + 1000
        try {
            runBlocking {
                withTimeoutOrNull(overallTimeoutMs) {
                    adminClientService.getSessionInfo(sid)
                }
            }
        } catch (e: Exception) {
            log.warn("[Router→Admin] Failed to get session info for $sid: ${e.message}")
            null
        }
    }
}
