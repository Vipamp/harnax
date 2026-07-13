package com.agnetix.harnax.client.single

import com.agnetix.harnax.client.HarnaxClientConfig
import java.time.Duration

/**
 * Builder for [SingleHarnaxClient].
 *
 * Usage:
 * ```
 * val client = SingleHarnaxClient.builder()
 *     .baseUrl("http://router-host:8081")
 *     .apiKey("your-api-key")
 *     .connectTimeout(Duration.ofSeconds(10))
 *     .readTimeout(Duration.ofSeconds(60))
 *     .streamTimeout(Duration.ofMinutes(15))
 *     .build()
 * ```
 */
class SingleClientBuilder {
    private var baseUrl: String = ""
    private var apiKey: String = ""
    private var connectTimeout: Duration = Duration.ofSeconds(5)
    private var readTimeout: Duration = Duration.ofSeconds(30)
    private var streamTimeout: Duration = Duration.ofMinutes(10)

    fun baseUrl(baseUrl: String) = apply { this.baseUrl = baseUrl }
    fun apiKey(apiKey: String) = apply { this.apiKey = apiKey }
    fun connectTimeout(timeout: Duration) = apply { this.connectTimeout = timeout }
    fun readTimeout(timeout: Duration) = apply { this.readTimeout = timeout }
    fun streamTimeout(timeout: Duration) = apply { this.streamTimeout = timeout }

    fun build(): SingleHarnaxClient {
        val config = HarnaxClientConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            connectTimeout = connectTimeout,
            readTimeout = readTimeout,
            streamTimeout = streamTimeout,
        )
        return SingleHarnaxClient(config)
    }
}
