package com.agnetix.harnax.scheduler.client

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.common.dto.ResultVo
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

@Component
class RouterClient(
    @Value($$"${scheduler.router-url:http://localhost:8081}") private val routerUrl: String,
    @Value($$"${scheduler.api-key:}") private val configuredApiKey: String,
    @Value($$"${scheduler.admin-url:http://localhost:8080}") private val adminUrl: String,
    @Value($$"${scheduler.admin-secret:}") private val adminSecret: String,
    @Value($$"${scheduler.timeout-seconds:300}") private val timeoutSeconds: Int,
) {
    private val log = LoggerFactory.getLogger(RouterClient::class.java)

    /** Resolved API key: configuredApiKey if set, otherwise auto-fetched from admin. */
    private lateinit var apiKey: String

    @PostConstruct
    fun init() {
        apiKey = if (configuredApiKey.isNotBlank()) {
            log.info("[Scheduler] Using configured API key from environment")
            configuredApiKey
        } else {
            log.info("[Scheduler] API key not configured, auto-fetching SYSTEM key from admin at {}", adminUrl)
            fetchSystemKeyFromAdmin()
        }
    }

    private fun fetchSystemKeyFromAdmin(): String {
        val client = RestClient.builder().baseUrl(adminUrl).build()
        val maxRetries = 5
        val retryDelayMs = 3000L

        for (attempt in 1..maxRetries) {
            try {
                val response: Map<String, Any?>? = client.post()
                    .uri("/api/admin/internal/api-keys/system-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer $adminSecret")
                    .body(mapOf("serviceName" to "scheduler"))
                    .retrieve()
                    .body(object : ParameterizedTypeReference<Map<String, Any?>>() {})

                val data = response?.get("data") as? Map<*, *>
                val rawKey = data?.get("rawKey") as? String
                if (rawKey != null) {
                    log.info("[Scheduler] SYSTEM API key fetched successfully, keyPrefix={}", data["keyPrefix"])
                    return rawKey
                }

                // Admin returned null data — key not yet initialized
                log.warn("[Scheduler] Admin returned no system key for scheduler (attempt {}/{}). initSystemKeys may not have completed yet.", attempt, maxRetries)
            } catch (e: Exception) {
                log.warn("[Scheduler] Failed to fetch system key from admin (attempt {}/{}): {}", attempt, maxRetries, e.message)
            }

            if (attempt < maxRetries) {
                Thread.sleep(retryDelayMs)
            }
        }

        throw IllegalStateException(
            "Failed to fetch SYSTEM API key from admin after $maxRetries attempts. " +
                "Ensure admin is running and initSystemKeys() has completed.",
        )
    }

    private val restClient: RestClient by lazy {
        val factory = org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofSeconds(timeoutSeconds.toLong()))
        }
        RestClient.builder()
            .requestFactory(factory)
            .build()
    }

    fun chat(sessionId: String, message: String): ChatResponse {
        val request = ChatAgentRequest(sessionId = sessionId, message = message)
        val url = "$routerUrl/api/router/agent/chat"

        log.info("[Scheduler→Router] POST {} - sessionId: {}", url, sessionId)
        val startTime = System.currentTimeMillis()

        val resultVo: ResultVo<ChatResponse>? = try {
            restClient.post()
                .uri(url)
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<ChatResponse>>() {})
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            log.error("[Scheduler←Router] Exception for session={}, elapsed={}ms: {}", sessionId, elapsed, e.message, e)
            throw RuntimeException("Failed to call router: ${e.message}", e)
        }

        val elapsed = System.currentTimeMillis() - startTime

        if (resultVo == null || resultVo.code != 200 || resultVo.data == null) {
            val errorMsg = resultVo?.message ?: "No response from router"
            log.error("[Scheduler←Router] Error for session={}, elapsed={}ms: {}", sessionId, elapsed, errorMsg)
            throw RuntimeException("Router returned error: $errorMsg")
        }

        log.info("[Scheduler\u2190Router] Success for session={}, elapsed={}ms", sessionId, elapsed)
        return resultVo.data!!
    }

    /**
     * Send a command (e.g. INTERRUPT) to the router for a specific session.
     */
    fun sendCommand(sessionId: String, command: CommandType) {
        val request = CommandAgentRequest(sessionId = sessionId, command = command)
        val url = "$routerUrl/api/router/agent/command"
        log.info("[Scheduler→Router] POST {} - command: {}", url, command)
        try {
            restClient.post()
                .uri(url)
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})
            log.info("[Scheduler←Router] Command {} sent for session={}", command, sessionId)
        } catch (e: Exception) {
            log.warn("[Scheduler←Router] Failed to send command {} for session={}: {}", command, sessionId, e.message)
        }
    }

    /**
     * Clear agent session cache on agent-service.
     * Called after task session completes to clean up cached agent.
     */
    fun clearSession(sessionId: String) {
        val url = "$routerUrl/api/router/agent/session/$sessionId"
        log.info("[Scheduler\u2192Router] DELETE {} - clearing session", url)
        try {
            restClient.delete()
                .uri(url)
                .header("X-Api-Key", apiKey)
                .retrieve()
                .toBodilessEntity()
            log.info("[Scheduler\u2190Router] Cleared session: {}", sessionId)
        } catch (e: Exception) {
            log.warn("[Scheduler\u2190Router] Failed to clear session={}, error={}", sessionId, e.message)
        }
    }
}
