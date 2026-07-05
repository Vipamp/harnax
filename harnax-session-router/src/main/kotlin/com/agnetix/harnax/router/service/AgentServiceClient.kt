package com.agnetix.harnax.router.service

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.common.dto.ResultVo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * Centralized HTTP client for all calls from Router to agent-service instances.
 *
 * All HTTP communication with agent-service is encapsulated here, separating
 * the transport concern from the routing logic (instance resolution, retry,
 * circuit-breaker, metrics) in [com.agnetix.harnax.router.proxy.SessionRouterService].
 *
 * Each method takes a [baseUrl] parameter because the target instance URL is
 * resolved dynamically by the router's instance registry.
 */
@Service
class AgentServiceClient(
    private val webClient: WebClient,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    @Value($$"${router.proxy.stream-timeout-minutes:10}")
    private val streamTimeoutMinutes: Long,
) {

    private val log = LoggerFactory.getLogger(AgentServiceClient::class.java)

    // ==================== Chat ====================

    /**
     * Send a blocking (non-streaming) chat request to agent-service.
     */
    suspend fun chat(baseUrl: String, request: ChatAgentRequest): ResultVo<ChatResponse> {
        val url = "$baseUrl/api/agent/chat"
        logRequest(url, request)
        val startTime = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) {
            restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<ChatResponse>>() {})
        } ?: ResultVo.error("No response from agent-service")
        val elapsed = System.currentTimeMillis() - startTime
        log.info(
            "[Router←Agent] Received agent response for session=${request.sessionId}, " +
                "code=${result.code}, success=${result.isSuccess()}, " +
                "contentLength=${result.data?.content?.length ?: 0}, elapsed=${elapsed}ms",
        )
        return result
    }

    /**
     * Send an SSE streaming chat request to agent-service.
     */
    fun chatStream(baseUrl: String, request: ChatAgentRequest, requestId: String): Flux<ChatEvent> {
        val url = "$baseUrl/api/agent/chat/stream"
        return webClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-Id", requestId)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(ChatEvent::class.java)
            .limitRate(10)
            .timeout(Duration.ofMinutes(streamTimeoutMinutes))
    }

    // ==================== Command ====================

    /**
     * Send a blocking command request to agent-service.
     */
    suspend fun command(baseUrl: String, request: CommandAgentRequest): ResultVo<CommandResponse> {
        val url = "$baseUrl/api/agent/command"
        logRequest(url, request)
        return withContext(Dispatchers.IO) {
            restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})
        } ?: ResultVo.error("No response from agent-service")
    }

    // ==================== Confirm ====================

    /**
     * Send a confirm request (SSE streaming) to agent-service.
     */
    fun confirmStream(baseUrl: String, request: ConfirmAgentRequest): Flux<ChatEvent> {
        val url = "$baseUrl/api/agent/confirm"
        return webClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(ChatEvent::class.java)
            .limitRate(10)
            .timeout(Duration.ofMinutes(streamTimeoutMinutes))
    }

    // ==================== Session ====================

    /**
     * Clear session data on agent-service (DELETE session).
     */
    suspend fun clearSession(baseUrl: String, sessionId: String): ResultVo<String> {
        val url = "$baseUrl/api/agent/session/$sessionId"
        return webClient.delete()
            .uri(url)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<String>>() {})
            .awaitSingleOrNull()
            ?: ResultVo.error("No response from agent-service")
    }

    /**
     * Load chat history for a session from agent-service.
     */
    suspend fun loadHistory(baseUrl: String, sessionId: String): ResultVo<List<Any>> {
        val url = "$baseUrl/api/agent/chat/history/$sessionId"
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<List<Any>>>() {})
            .awaitSingleOrNull()
            ?: ResultVo.error("No response from agent-service")
    }

    /**
     * Load all plans for a session from agent-service.
     */
    suspend fun loadPlans(baseUrl: String, sessionId: String): ResultVo<List<Any>> {
        val url = "$baseUrl/api/agent/session/$sessionId/plans"
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<List<Any>>>() {})
            .awaitSingleOrNull()
            ?: ResultVo.error("No response from agent-service")
    }

    /**
     * Load the current plan for a session from agent-service.
     */
    suspend fun loadCurrentPlan(baseUrl: String, sessionId: String): ResultVo<Any?> {
        val url = "$baseUrl/api/agent/session/$sessionId/current-plan"
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<Any?>>() {})
            .awaitSingleOrNull()
            ?: ResultVo.error("No response from agent-service")
    }

    // ==================== Workspace ====================

    /**
     * List files in a workspace directory on agent-service.
     */
    suspend fun workspaceListFiles(baseUrl: String, sessionId: String, path: String): ResultVo<List<Map<String, Any>>> = try {
        val response = webClient.get()
            .uri { builder ->
                builder.scheme("http")
                    .host(extractHost(baseUrl))
                    .port(extractPort(baseUrl))
                    .path("/api/agent/workspace/$sessionId/files")
                    .queryParam("path", path)
                    .build()
            }
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<List<Map<String, Any>>>>() {})
            .awaitSingleOrNull()
        response ?: ResultVo.error("No response from agent-service")
    } catch (e: Exception) {
        log.error("[Router→Agent] workspaceListFiles failed for session=$sessionId: ${e.message}")
        ResultVo.error("Agent workspace list failed: ${extractErrorMessage(e)}")
    }

    /**
     * Read a file from workspace on agent-service.
     */
    suspend fun workspaceReadFile(baseUrl: String, sessionId: String, path: String): ResultVo<Map<String, Any>> = try {
        val response = webClient.get()
            .uri { builder ->
                builder.scheme("http")
                    .host(extractHost(baseUrl))
                    .port(extractPort(baseUrl))
                    .path("/api/agent/workspace/$sessionId/read")
                    .queryParam("path", path)
                    .build()
            }
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<Map<String, Any>>>() {})
            .awaitSingleOrNull()
        response ?: ResultVo.error("No response from agent-service")
    } catch (e: Exception) {
        log.error("[Router→Agent] workspaceReadFile failed for session=$sessionId, path=$path: ${e.message}")
        ResultVo.error("Agent workspace read failed: ${extractErrorMessage(e)}")
    }

    /**
     * Get workspace status for one or multiple sessions from agent-service.
     */
    suspend fun workspaceStatus(baseUrl: String, sessionIds: String): ResultVo<Map<String, Map<String, Any>>> = try {
        val uri = java.net.URI.create("$baseUrl").resolve("/api/agent/workspace/status")
        log.info("[AgentServiceClient] Forwarding workspace status request for sessions: $sessionIds")
        val response = webClient.get()
            .uri { builder ->
                builder.scheme("http")
                    .host(uri.host)
                    .port(uri.port)
                    .path("/api/agent/workspace/status")
                    .queryParam("sessionIds", sessionIds)
                    .build()
            }
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<Map<String, Map<String, Any>>>>() {})
            .awaitSingleOrNull()

        log.debug("[AgentServiceClient] Workspace status response: code=${response?.code}")
        response ?: ResultVo.error("No response from agent-service")
    } catch (e: Exception) {
        log.error("[Router→Agent] workspaceStatus failed for sessions=$sessionIds: ${e.message}")
        ResultVo.error("Agent workspace status failed: ${extractErrorMessage(e)}")
    }

    /**
     * Upload a file to workspace on agent-service.
     */
    suspend fun workspaceUpload(
        baseUrl: String,
        sessionId: String,
        path: String,
        fileName: String,
        fileBytes: ByteArray,
    ): ResultVo<Map<String, Any>> {
        val uri = java.net.URI("$baseUrl/api/agent/workspace/$sessionId/upload")
        val fileResource = object : org.springframework.core.io.ByteArrayResource(fileBytes) {
            override fun getFilename(): String = fileName
        }
        val body = org.springframework.http.client.MultipartBodyBuilder()
        body.part("file", fileResource)
        body.part("path", path)

        return try {
            webClient.post()
                .uri(uri)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(body.build())
                .retrieve()
                .bodyToMono(object : ParameterizedTypeReference<ResultVo<Map<String, Any>>>() {})
                .awaitSingleOrNull()
                ?: ResultVo.error("No response from agent-service")
        } catch (e: Exception) {
            log.error("[Router→Agent] workspaceUpload failed for session=$sessionId, fileName=$fileName: ${e.message}")
            ResultVo.error("Agent workspace upload failed: ${extractErrorMessage(e)}")
        }
    }

    /**
     * Download a file from workspace on agent-service.
     *
     * @return a Pair of (file bytes, content type string), or null if the response was empty.
     */
    suspend fun workspaceDownload(baseUrl: String, sessionId: String, path: String): Pair<ByteArray, String>? = try {
        val response = webClient.get()
            .uri { builder ->
                builder.scheme("http")
                    .host(extractHost(baseUrl))
                    .port(extractPort(baseUrl))
                    .path("/api/agent/workspace/$sessionId/download")
                    .queryParam("path", path)
                    .build()
            }
            .retrieve()
            .toEntity(ByteArray::class.java)
            .awaitSingleOrNull()

        if (response != null && response.body != null) {
            val contentType = response.headers.contentType?.toString() ?: MediaType.APPLICATION_OCTET_STREAM_VALUE
            val body = response.body ?: return null
            Pair(body, contentType)
        } else {
            null
        }
    } catch (e: Exception) {
        log.error("[Router→Agent] workspaceDownload failed for session=$sessionId, path=$path: ${e.message}")
        throw e
    }

    // ==================== Internal helpers ====================

    /**
     * Extract a concise error message from exceptions, including HTTP status for WebClientResponseException.
     */
    private fun extractErrorMessage(e: Exception): String {
        if (e is org.springframework.web.reactive.function.client.WebClientResponseException) {
            return "HTTP ${e.statusCode.value()}: ${e.responseBodyAsString.take(200)}"
        }
        return e.message ?: e.javaClass.simpleName
    }

    private fun logRequest(url: String, request: Any) {
        if (log.isDebugEnabled) {
            val json = objectMapper.writeValueAsString(request)
            log.debug("[Router→Agent] POST $url, body=$json")
        } else {
            log.info("[Router→Agent] POST $url, type=${request.javaClass.simpleName}")
        }
    }

    private fun extractHost(baseUrl: String): String = java.net.URI.create(baseUrl).host

    private fun extractPort(baseUrl: String): Int = java.net.URI.create(baseUrl).port
}
