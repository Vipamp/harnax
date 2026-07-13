package com.agnetix.harnax.client

import java.time.Duration

/**
 * Configuration for the Harnax client SDK.
 *
 * @property baseUrl    Router base URL (e.g. "http://router-host:8081")
 * @property apiKey     API key for X-Api-Key authentication
 * @property connectTimeout HTTP connection timeout
 * @property readTimeout    HTTP read timeout for non-streaming requests
 * @property streamTimeout  Timeout for SSE streaming requests
 */
data class HarnaxClientConfig(
    val baseUrl: String,
    val apiKey: String,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(30),
    val streamTimeout: Duration = Duration.ofMinutes(10),
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
    }

    /**
     * Normalized base URL with trailing slash removed.
     */
    val normalizedBaseUrl: String = baseUrl.trimEnd('/')
}
