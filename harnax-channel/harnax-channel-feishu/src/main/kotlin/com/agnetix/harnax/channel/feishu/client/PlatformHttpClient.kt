package com.agnetix.harnax.channel.feishu.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * Platform HTTP Client
 * Unified HTTP client encapsulated based on Spring WebFlux WebClient
 * Used to send messages to Feishu Open API
 *
 * Features:
 * - Shared connection pool
 * - Timeout configuration (connect 5s, read 10s)
 * - Automatic retry (up to 3 times, exponential backoff)
 * - Response validation
 */
class PlatformHttpClient(
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 1000,
    private val retryMultiplier: Double = 2.0,
    private val maxDelayMs: Long = 10000,
    private val connectTimeoutMs: Long = 5000,
    private val readTimeoutMs: Long = 10000,
) {
    private val logger = LoggerFactory.getLogger(PlatformHttpClient::class.java)
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private val webClient: WebClient = WebClient.builder()
        .codecs { config -> config.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) } // 10MB
        .build()

    /**
     * Send JSON POST request
     *
     * @param url Target URL
     * @param body Request body object (will be serialized to JSON)
     * @param headers Additional HTTP Headers
     * @return PlatformResponse response object
     */
    suspend fun postJson(
        url: String,
        body: Any,
        headers: Map<String, String> = emptyMap(),
    ): PlatformResponse {
        val jsonBody = objectMapper.writeValueAsString(body)
        logger.debug("POST to $url with body: ${jsonBody.take(200)}")

        return executeWithRetry(url) { attempt ->
            logger.debug("Attempt $attempt: POST $url")

            try {
                val responseMono = webClient.post()
                    .uri(url)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .apply {
                        headers.forEach { (key, value) -> header(key, value) }
                    }
                    .bodyValue(jsonBody)
                    .retrieve()
                    .toEntity(String::class.java)

                val responseEntity = responseMono.block(Duration.ofMillis(readTimeoutMs))
                    ?: throw RuntimeException("No response received")

                val statusCode = responseEntity.statusCode.value()
                val responseBody = responseEntity.body ?: ""

                if (statusCode in 200..299) {
                    PlatformResponse.Success(
                        statusCode = statusCode,
                        body = responseBody,
                    )
                } else {
                    PlatformResponse.Error(
                        statusCode = statusCode,
                        body = responseBody,
                    )
                }
            } catch (e: Exception) {
                throw RuntimeException("HTTP request failed: ${e.message}", e)
            }
        }
    }

    /**
     * Executor with retry logic
     */
    private suspend fun executeWithRetry(
        url: String,
        block: suspend (Int) -> PlatformResponse,
    ): PlatformResponse {
        var lastError: PlatformResponse.Error? = null

        for (attempt in 1..maxRetries) {
            try {
                val response = block(attempt)

                if (response is PlatformResponse.Success) {
                    return response
                }

                lastError = response as PlatformResponse.Error

                if (!isRetryableError(response.statusCode)) {
                    return response
                }

                if (attempt < maxRetries) {
                    val delayMs = calculateDelay(attempt)
                    logger.warn("Retryable error on attempt $attempt for $url, retrying in ${delayMs}ms")
                    Thread.sleep(delayMs)
                }
            } catch (e: Exception) {
                logger.error("Exception on attempt $attempt for $url: ${e.message}", e)
                lastError = PlatformResponse.Error(
                    statusCode = 0,
                    body = "",
                    exception = e,
                )

                if (attempt < maxRetries) {
                    val delayMs = calculateDelay(attempt)
                    logger.warn("Exception on attempt $attempt, retrying in ${delayMs}ms")
                    Thread.sleep(delayMs)
                }
            }
        }

        return lastError ?: PlatformResponse.Error(
            statusCode = 0,
            body = "",
            platformMessage = "All retries exhausted",
        )
    }

    /**
     * Determine if error is retryable
     */
    private fun isRetryableError(statusCode: Int): Boolean = statusCode == 429 ||
        statusCode == 500 ||
        statusCode == 502 ||
        statusCode == 503 ||
        statusCode == 504

    /**
     * Calculate retry delay (exponential backoff)
     */
    private fun calculateDelay(attempt: Int): Long {
        val delay = initialDelayMs * Math.pow(retryMultiplier, (attempt - 1).toDouble()).toLong()
        return minOf(delay, maxDelayMs)
    }
}
