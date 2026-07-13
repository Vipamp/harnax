package com.agnetix.harnax.client.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Spring Boot configuration properties for Harnax client.
 *
 * Configuration example:
 * ```yaml
 * harnax:
 *   client:
 *     base-url: http://router-host:8081
 *     api-key: your-api-key
 *     connect-timeout: 5s
 *     read-timeout: 30s
 *     stream-timeout: 10m
 * ```
 */
@ConfigurationProperties(prefix = "harnax.client")
data class HarnaxClientProperties(
    /** Router base URL */
    var baseUrl: String = "",
    /** API key for X-Api-Key authentication */
    var apiKey: String = "",
    /** HTTP connection timeout */
    var connectTimeout: Duration = Duration.ofSeconds(5),
    /** HTTP read timeout for non-streaming requests */
    var readTimeout: Duration = Duration.ofSeconds(30),
    /** Timeout for SSE streaming requests */
    var streamTimeout: Duration = Duration.ofMinutes(10),
)
