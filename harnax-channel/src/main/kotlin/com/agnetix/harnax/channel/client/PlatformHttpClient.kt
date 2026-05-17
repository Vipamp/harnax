package com.agnetix.harnax.channel.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * 平台 HTTP 客户端
 * 基于 Spring WebFlux WebClient 封装的统一 HTTP 客户端
 * 用于向各平台 webhook 发送消息
 *
 * 特性：
 * - 共享连接池
 * - 超时配置（连接 5s，读取 10s）
 * - 自动重试（最多 3 次，指数退避）
 * - 响应验证
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
     * 发送 JSON POST 请求
     *
     * @param url 目标 URL
     * @param body 请求体对象（会被序列化为 JSON）
     * @param headers 额外的 HTTP Headers
     * @return PlatformResponse 响应对象
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
     * 发送带签名的 JSON POST 请求（钉钉专用）
     *
     * @param baseUrl 基础 URL（不含查询参数）
     * @param body 请求体对象
     * @param appSecret 应用密钥（用于生成签名）
     * @return PlatformResponse 响应对象
     */
    suspend fun postWithSign(
        baseUrl: String,
        body: Any,
        appSecret: String,
    ): PlatformResponse {
        val timestamp = System.currentTimeMillis()
        val sign = generateSign(timestamp, appSecret)
        val url = "$baseUrl&timestamp=$timestamp&sign=$sign"

        logger.debug("POST to DingTalk with signed URL")
        return postJson(url, body)
    }

    /**
     * 生成钉钉签名
     * HmacSHA256(timestamp + "\n" + appSecret)，然后 Base64 + URL 编码
     */
    private fun generateSign(timestamp: Long, appSecret: String): String {
        val stringToSign = "$timestamp\n$appSecret"
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretKey = javax.crypto.spec.SecretKeySpec(
            appSecret.toByteArray(Charsets.UTF_8),
            "HmacSHA256",
        )
        mac.init(secretKey)
        val hash = mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8))
        val base64 = java.util.Base64.getEncoder().encodeToString(hash)
        return java.net.URLEncoder.encode(base64, Charsets.UTF_8.toString())
    }

    /**
     * 带重试的执行器
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

                // 如果是错误响应，记录下来并可能重试
                lastError = response as PlatformResponse.Error

                // 检查是否是可重试的错误（如 429 频率限制、5xx 服务器错误）
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
     * 判断错误是否可重试
     */
    private fun isRetryableError(statusCode: Int): Boolean {
        return statusCode == 429 ||
            // 频率限制
            statusCode == 500 ||
            // 服务器内部错误
            statusCode == 502 ||
            // 网关错误
            statusCode == 503 ||
            // 服务不可用
            statusCode == 504 // 网关超时
    }

    /**
     * 计算重试延迟（指数退避）
     */
    private fun calculateDelay(attempt: Int): Long {
        val delay = initialDelayMs * Math.pow(retryMultiplier, (attempt - 1).toDouble()).toLong()
        return minOf(delay, maxDelayMs)
    }
}
