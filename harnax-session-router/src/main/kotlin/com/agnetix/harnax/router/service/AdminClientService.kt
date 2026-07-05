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
    suspend fun getSessionInfo(sessionId: String): SessionInfo? = try {
        val result = webClient.get()
            .uri("/api/admin/internal/sessions/$sessionId/info")
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<SessionInfo?>>() {})
            .awaitSingleOrNull()

        result?.data
    } catch (e: WebClientResponseException) {
        val status = e.statusCode.value()
        val body = e.responseBodyAsString.take(200)
        if (status == 401) {
            log.error(
                "[Router→Admin] Authentication failed (401) calling $adminUrl/api/admin/internal/sessions/$sessionId/info. " +
                    "Check admin.internal-api.secret matches admin's config. Response: $body",
            )
        } else {
            log.warn("[Router→Admin] HTTP $status calling $adminUrl/api/admin/internal/sessions/$sessionId/info. Response: $body")
        }
        null
    } catch (e: Exception) {
        log.warn("[Router→Admin] Failed to get session info for $sessionId from $adminUrl: ${e.message}")
        null
    }

    /**
     * DTO matching the Admin service's API key validation response.
     */
    data class ApiKeyValidateResponse(
        val name: String = "",
        val keyHash: String = "",
        val scopes: String = "",
        val tenantId: Long? = null,
        val rateLimit: Int = 60,
        val enabled: Boolean = true,
        val expiresAt: String? = null,
    )

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
