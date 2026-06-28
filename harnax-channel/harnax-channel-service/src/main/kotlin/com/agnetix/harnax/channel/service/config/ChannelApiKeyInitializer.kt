package com.agnetix.harnax.channel.service.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

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
        val url = "$adminUrl/api/admin/internal/api-keys/system-key"
        val requestBody = mapOf("serviceName" to "channel-service")

        val restClient = RestClient.builder()
            .baseUrl(adminUrl)
            .build()

        val response: Map<String, Any?>? = try {
            restClient.post()
                .uri("/api/admin/internal/api-keys/system-key")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer $adminSecret")
                .body(requestBody)
                .retrieve()
                .body(object : org.springframework.core.ParameterizedTypeReference<Map<String, Any?>>() {})
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch system API key from admin: ${e.message}", e)
        }

        val data = response?.get("data") as? Map<*, *>
            ?: throw IllegalStateException("Admin returned no data for system key")
        val rawKey = data["rawKey"] as? String
            ?: throw IllegalStateException("Admin returned no rawKey for system key")

        log.info("System API key fetched successfully, keyPrefix={}", data["keyPrefix"])
        return rawKey
    }
}
