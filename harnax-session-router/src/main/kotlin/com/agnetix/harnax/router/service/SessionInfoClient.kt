package com.agnetix.harnax.router.service

import com.agnetix.harnax.common.dto.ResultVo
import com.github.benmanes.caffeine.cache.Caffeine
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

data class SessionInfo(
    val sessionId: String = "",
    val agentId: Long? = null,
    val agentName: String? = null,
    val modelId: Long? = null,
    val modelName: String? = null,
    val tenantId: Long? = null,
)

@Component
class SessionInfoClient(
    @Value("\${admin.service.url:http://localhost:8080}")
    private val adminUrl: String,
    @Value("\${admin.internal-api.secret:}")
    private val adminSecret: String,
    @Value("\${admin.internal-api.timeout-connect-ms:2000}")
    private val connectTimeoutMs: Long,
    @Value("\${admin.internal-api.timeout-response-ms:3000}")
    private val responseTimeoutMs: Long,
) {

    private val log = LoggerFactory.getLogger(SessionInfoClient::class.java)

    // Reactive client based on WebClient + Netty; runBlocking keeps the external signature synchronous.
    // Two timeouts are enforced:
    //   - connectTimeoutMs: TCP connect timeout
    //   - responseTimeoutMs: overall response timeout (includes reading the body)
    private val webClient: WebClient = WebClient.builder()
        .baseUrl(adminUrl)
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .responseTimeout(Duration.ofMillis(responseTimeoutMs))
                    .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs.toInt()),
            ),
        )
        .defaultHeader("Authorization", "Bearer $adminSecret")
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    private val cache = Caffeine.newBuilder()
        .maximumSize(5000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build<String, SessionInfo?>()

    fun getSessionInfo(sessionId: String): SessionInfo? = cache.get(sessionId) { sid ->
        // Overall request budget: slightly above responseTimeoutMs to leave scheduler slack.
        val overallTimeoutMs = responseTimeoutMs + 1000
        try {
            runBlocking {
                withTimeoutOrNull(overallTimeoutMs) {
                    webClient.get()
                        .uri("/api/internal/sessions/$sid/info")
                        .retrieve()
                        .bodyToMono(object : ParameterizedTypeReference<ResultVo<SessionInfo?>>() {})
                        .awaitSingleOrNull()
                }
            }?.data
        } catch (e: WebClientResponseException) {
            val status = e.statusCode.value()
            val body = e.responseBodyAsString.take(200)
            if (status == 401) {
                log.error(
                    "[Router→Admin] Authentication failed (401) calling $adminUrl/api/internal/sessions/$sid/info. " +
                        "Check admin.internal-api.secret matches admin's config. Response: $body",
                )
            } else {
                log.warn("[Router→Admin] HTTP $status calling $adminUrl/api/internal/sessions/$sid/info. Response: $body")
            }
            null
        } catch (e: Exception) {
            log.warn("[Router→Admin] Failed to get session info for $sid from $adminUrl: ${e.message}")
            null
        }
    }
}
