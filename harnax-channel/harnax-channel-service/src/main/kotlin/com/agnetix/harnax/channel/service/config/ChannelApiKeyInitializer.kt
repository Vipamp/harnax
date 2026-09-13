package com.agnetix.harnax.channel.service.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Holds the API key used by Channel service to authenticate with Router.
 */
class ChannelRouterApiKey(val rawKey: String)

/**
 * Initializes the Channel→Router API key on startup.
 * Supports two modes:
 *   1. Static: read from environment variable CHANNEL_API_KEY
 *   2. Auto-fetch: call Admin's internal API to retrieve the SYSTEM key
 */
@Configuration
class ChannelApiKeyInitializer(
    @Value("\${channel.router-api-key:}")
    private val configuredApiKey: String,
    @Value("\${channel.auto-fetch-system-key:true}")
    private val autoFetch: Boolean,
    @Value("\${channel.admin.url:http://localhost:8080}")
    private val adminUrl: String,
    @Value("\${admin.internal-api.secret:}")
    private val adminSecret: String,
) {
    private val log = LoggerFactory.getLogger(ChannelApiKeyInitializer::class.java)

    @Bean
    fun channelRouterApiKey(): ChannelRouterApiKey {
        val key = if (configuredApiKey.isNotBlank()) {
            log.info("Using configured channel router API key (from environment)")
            configuredApiKey
        } else if (autoFetch) {
            log.info("Auto-fetching system API key from admin service at {}", adminUrl)
            fetchSystemKeyFromAdmin()
        } else {
            throw IllegalStateException(
                "Channel router API key not configured. " +
                    "Set CHANNEL_API_KEY env var or enable channel.auto-fetch-system-key=true",
            )
        }
        return ChannelRouterApiKey(key)
    }

    private fun fetchSystemKeyFromAdmin(): String {
        val restClient = RestClient.builder()
            .baseUrl(adminUrl)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofMillis(FETCH_CONNECT_TIMEOUT_MS))
                    setReadTimeout(Duration.ofMillis(FETCH_READ_TIMEOUT_MS))
                },
            )
            .build()

        // Admin and channel-service come up together in compose and in a rollout, so admin can be
        // briefly unreachable. One failed attempt here fails bean creation and takes the whole
        // service down, which is worse than the restart an orchestrator would have done anyway.
        var lastError: Exception? = null
        for (attempt in 1..FETCH_ATTEMPTS) {
            try {
                return requestSystemKey(restClient)
            } catch (e: Exception) {
                lastError = e
                if (attempt == FETCH_ATTEMPTS) break
                log.warn(
                    "System API key fetch attempt {}/{} failed: {}; retrying in {}ms",
                    attempt,
                    FETCH_ATTEMPTS,
                    e.message,
                    FETCH_RETRY_BACKOFF_MS * attempt,
                )
                try {
                    Thread.sleep(FETCH_RETRY_BACKOFF_MS * attempt)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw IllegalStateException(
            "Failed to fetch the system API key from admin at $adminUrl after $FETCH_ATTEMPTS attempt(s)",
            lastError,
        )
    }

    private fun requestSystemKey(restClient: RestClient): String {
        val response: Map<String, Any?>? = restClient.post()
            .uri(SYSTEM_KEY_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer $adminSecret")
            .body(mapOf("serviceName" to SERVICE_NAME))
            .retrieve()
            .body(object : ParameterizedTypeReference<Map<String, Any?>>() {})

        val data = response?.get("data") as? Map<*, *>
            ?: throw IllegalStateException("Admin returned no data for system key")
        val rawKey = data["rawKey"] as? String
            ?: throw IllegalStateException("Admin returned no rawKey for system key")

        log.info("System API key fetched successfully, keyPrefix={}", data["keyPrefix"])
        return rawKey
    }

    companion object {
        private const val SYSTEM_KEY_PATH = "/api/admin/internal/api-keys/system-key"
        private const val SERVICE_NAME = "channel-service"

        private const val FETCH_CONNECT_TIMEOUT_MS = 3_000L
        private const val FETCH_READ_TIMEOUT_MS = 5_000L
        private const val FETCH_ATTEMPTS = 3
        private const val FETCH_RETRY_BACKOFF_MS = 1_000L
    }
}
