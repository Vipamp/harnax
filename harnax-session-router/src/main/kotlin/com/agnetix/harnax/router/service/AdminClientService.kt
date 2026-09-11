package com.agnetix.harnax.router.service

import com.agnetix.harnax.common.dto.ResultVo
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * Centralized HTTP client for all calls from Router to the Admin service.
 *
 * All admin-related HTTP calls (API key validation, session info lookup, etc.)
 * are consolidated here to avoid scattered RestTemplate/WebClient instances
 * with duplicated configuration (adminUrl, adminSecret, timeouts).
 */
@Service
class AdminClientService(
    @Value("\${admin.service.url:http://localhost:8080}")
    private val adminUrl: String,
    @Value("\${admin.internal-api.secret:}")
    private val adminSecret: String,
    @Value("\${admin.internal-api.timeout-connect-ms:2000}")
    private val connectTimeoutMs: Long,
    @Value("\${admin.internal-api.timeout-response-ms:3000}")
    private val responseTimeoutMs: Long,
) {

    private val log = LoggerFactory.getLogger(AdminClientService::class.java)

    private val webClient: WebClient = WebClient.builder()
        .baseUrl(adminUrl)
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(Duration.ofMillis(responseTimeoutMs))
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs.toInt()),
            ),
        )
        .defaultHeader("Authorization", "Bearer $adminSecret")
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    /**
     * Validate an API key by calling the Admin service's internal endpoint.
     *
     * @param keyHash the SHA-256 hash of the API key
     * @return the validation response, or null if the call failed or the key was not found
     */
    suspend fun validateApiKey(keyHash: String): ApiKeyValidateResponse? = try {
        val result = webClient.post()
            .uri("/api/admin/internal/api-keys/validate")
            .bodyValue(mapOf("keyHash" to keyHash))
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<ApiKeyValidateResponse?>>() {})
            .awaitSingleOrNull()

        result?.data
    } catch (e: WebClientResponseException) {
        val status = e.statusCode.value()
        val body = e.responseBodyAsString.take(200)
        if (status == 401) {
            log.error(
                "[Router→Admin] Authentication failed (401) calling $adminUrl/api/admin/internal/api-keys/validate. " +
                    "Check admin.internal-api.secret matches admin's config. Response: $body",
            )
        } else {
            log.warn("[Router→Admin] HTTP $status calling $adminUrl/api/admin/internal/api-keys/validate. Response: $body")
        }
        null
    } catch (e: Exception) {
        log.warn("[Router→Admin] Failed to validate API key from $adminUrl: ${e.message}")
        null
    }

    /**
     * Get session info (agentId, agentName, modelId, etc.) from the Admin service.
     *
     * @param sessionId the session ID to look up
     * @return the session info, or null if the call failed or the session was not found
     */
    suspend fun getSessionInfo(sessionId: String): SessionInfo? = (lookupSession(sessionId) as? SessionLookup.Found)?.info

    /**
     * Same lookup, with the reason for an absent session kept distinguishable.
     *
     * Telling [SessionLookup.Unknown] from [SessionLookup.Unreachable] is the whole point: a caller
     * that collapses them lets one admin hiccup be cached for minutes as if the session had ceased to
     * exist, and lets a caller that enforces session ownership either fail every request during an
     * outage or fail open on a session admin genuinely does not know.
     *
     * The id goes in as a path segment rather than inside the URI string: `WebClient.uri(String)`
     * treats its argument as a template, so an interpolated id could re-point the call at another
     * internal endpoint — carrying this client's `Authorization` header with it.
     */
    suspend fun lookupSession(sessionId: String): SessionLookup = try {
        val result = webClient.get()
            .uri { builder ->
                builder.path("/api/admin/internal/sessions").pathSegment(sessionId).path("info").build()
            }
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<SessionInfo?>>() {})
            .awaitSingleOrNull()

        result?.data?.let { SessionLookup.Found(it) } ?: SessionLookup.Unknown
    } catch (e: WebClientResponseException) {
        val status = e.statusCode.value()
        val body = e.responseBodyAsString.take(200)
        if (status == 401) {
            log.error(
                "[Router→Admin] Authentication failed (401) calling $adminUrl's internal session-info endpoint. " +
                    "Check admin.internal-api.secret matches admin's config. Response: $body",
            )
        } else {
            log.warn("[Router→Admin] HTTP $status calling $adminUrl's internal session-info endpoint. Response: $body")
        }
        // A 404 is admin answering: the session is not there, which is a different fact from admin
        // being unreachable or refusing to talk to us.
        if (status == 404) SessionLookup.Unknown else SessionLookup.Unreachable
    } catch (e: Exception) {
        log.warn("[Router→Admin] Failed to look up a session at $adminUrl: ${e.message}")
        SessionLookup.Unreachable
    }

    /**
     * DTO matching the Admin service's API key validation response.
     */
    data class ApiKeyValidateResponse(
        val name: String = "",
        /** Key owner; absent for SYSTEM keys. */
        val userId: Long? = null,
        val keyHash: String = "",
        val scopes: String = "",
        val tenantId: Long? = null,
        val rateLimit: Int = 60,
        val enabled: Boolean = true,
        val expiresAt: String? = null,
    )

    /**
     * Outcome of a session lookup, with admin's silence kept apart from admin's answer.
     */
    sealed class SessionLookup {
        data class Found(val info: SessionInfo) : SessionLookup()

        /** Admin answered, and there is no such session. */
        object Unknown : SessionLookup()

        /** Admin could not be reached, refused the call, or failed — nothing is known. */
        object Unreachable : SessionLookup()
    }

    /**
     * DTO for session info returned by the Admin service.
     */
    data class SessionInfo(
        val sessionId: String = "",
        val agentId: Long? = null,
        val agentName: String? = null,
        val modelId: Long? = null,
        val modelName: String? = null,
        val tenantId: Long? = null,
    )
}
