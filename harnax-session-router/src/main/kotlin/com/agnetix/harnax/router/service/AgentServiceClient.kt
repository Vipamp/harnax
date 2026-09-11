package com.agnetix.harnax.router.service

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.common.dto.ResultVo
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.UriUtils
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * Centralized HTTP client for all calls from Router to agent-service instances.
 *
 * All HTTP communication with agent-service is encapsulated here, separating
 * the transport concern from the routing logic (instance resolution, retry,
 * circuit-breaker, metrics) in [com.agnetix.harnax.router.proxy.SessionRouterService].
 *
 * Each method takes a [baseUrl] parameter because the target instance URL is resolved dynamically by
 * the router's instance registry.
 *
 * A call that cannot be answered throws. Catching it here and returning an error envelope instead
 * would hide the failure from the router's failover, its breaker and its metrics — and the router is
 * the only place that knows whether another instance should be tried.
 */
@Service
class AgentServiceClient(
    private val webClient: WebClient,
    @Qualifier("streamingWebClient")
    private val streamingWebClient: WebClient,
    private val objectMapper: ObjectMapper,
    // Reactor's `timeout` measures silence between events, not the length of the stream, so the two
    // limits are separate: a stream that keeps answering is not cut off at 120s, and a stream that
    // stops answering is not held open for the whole 30 minutes.
    @Value($$"${router.proxy.stream-idle-timeout-seconds:120}")
    private val streamIdleTimeoutSeconds: Long,
    @Value($$"${router.proxy.stream-max-duration-minutes:30}")
    private val streamMaxDurationMinutes: Long,
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
        val result = webClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<ChatResponse>>() {})
            .awaitSingleOrNull()
            ?: throw emptyBody(url)
        val elapsed = System.currentTimeMillis() - startTime
        log.info(
            "[Router←Agent] Received agent response for session=${request.sessionId}, " +
                "code=${result.code}, success=${result.isSuccess()}, " +
                "contentLength=${result.data?.content?.length ?: 0}, " +
                "attachments=${result.data?.attachments?.size ?: 0}, elapsed=${elapsed}ms",
        )
        return result
    }

    /**
     * Send an SSE streaming chat request to agent-service.
     */
    fun chatStream(baseUrl: String, request: ChatAgentRequest, requestId: String): Flux<ChatEvent> {
        val url = "$baseUrl/api/agent/chat/stream"
        return streamingWebClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-Id", requestId)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(ChatEvent::class.java)
            .limitRate(10)
            .withinStreamLimits()
    }

    // ==================== Command ====================

    /**
     * Send a blocking command request to agent-service.
     */
    suspend fun command(baseUrl: String, request: CommandAgentRequest): ResultVo<CommandResponse> {
        val url = "$baseUrl/api/agent/command"
        logRequest(url, request)
        return webClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})
            .awaitSingleOrNull()
            ?: throw emptyBody(url)
    }

    // ==================== Confirm ====================

    /**
     * Send a confirm request (SSE streaming) to agent-service.
     */
    fun confirmStream(baseUrl: String, request: ConfirmAgentRequest): Flux<ChatEvent> {
        val url = "$baseUrl/api/agent/confirm"
        return streamingWebClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(ChatEvent::class.java)
            .limitRate(10)
            .withinStreamLimits()
    }

    // ==================== Session ====================

    /**
     * Clear session data on agent-service (DELETE session).
     */
    suspend fun clearSession(baseUrl: String, sessionId: String): ResultVo<String> {
        val url = agentUrl(baseUrl, "/api/agent/session/${segment(sessionId)}")
        return webClient.delete()
            .uri(url)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<String>>() {})
            .awaitSingleOrNull()
            ?: throw emptyBody(url.toString())
    }

    /**
     * Load chat history for a session from agent-service.
     */
    suspend fun loadHistory(baseUrl: String, sessionId: String): ResultVo<List<Any>> {
        val url = agentUrl(baseUrl, "/api/agent/chat/history/${segment(sessionId)}")
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<List<Any>>>() {})
            .awaitSingleOrNull()
            ?: throw emptyBody(url.toString())
    }

    /**
     * Load all plans for a session from agent-service.
     */
    suspend fun loadPlans(baseUrl: String, sessionId: String): ResultVo<List<Any>> {
        val url = agentUrl(baseUrl, "/api/agent/session/${segment(sessionId)}/plans")
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<List<Any>>>() {})
            .awaitSingleOrNull()
            ?: throw emptyBody(url.toString())
    }

    /**
     * Load the current plan for a session from agent-service.
     */
    suspend fun loadCurrentPlan(baseUrl: String, sessionId: String): ResultVo<Any?> {
        val url = agentUrl(baseUrl, "/api/agent/session/${segment(sessionId)}/current-plan")
        return webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<Any?>>() {})
            .awaitSingleOrNull()
            ?: throw emptyBody(url.toString())
    }

    // ==================== Workspace ====================

    /**
     * List files in a workspace directory on agent-service.
     */
    suspend fun workspaceListFiles(
        baseUrl: String,
        sessionId: String,
        path: String,
    ): ResultVo<List<Map<String, Any>>> {
        val url = agentUrl(
            baseUrl,
            "/api/agent/workspace/${segment(sessionId)}/files",
            mapOf("path" to path),
        )
        val response = webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<List<Map<String, Any>>>>() {})
            .awaitSingleOrNull()
        return response ?: throw emptyBody(url.toString())
    }

    /**
     * Read a file from workspace on agent-service.
     */
    suspend fun workspaceReadFile(
        baseUrl: String,
        sessionId: String,
        path: String,
    ): ResultVo<Map<String, Any>> {
        val url = agentUrl(
            baseUrl,
            "/api/agent/workspace/${segment(sessionId)}/read",
            mapOf("path" to path),
        )
        val response = webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<Map<String, Any>>>() {})
            .awaitSingleOrNull()
        return response ?: throw emptyBody(url.toString())
    }

    /**
     * Get workspace status for one or multiple sessions from agent-service.
     */
    suspend fun workspaceStatus(
        baseUrl: String,
        sessionIds: String,
    ): ResultVo<Map<String, Map<String, Any>>> {
        val url = agentUrl(baseUrl, "/api/agent/workspace/status", mapOf("sessionIds" to sessionIds))
        val response = webClient.get()
            .uri(url)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<Map<String, Map<String, Any>>>>() {})
            .awaitSingleOrNull()
        return response ?: throw emptyBody(url.toString())
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
        val url = agentUrl(baseUrl, "/api/agent/workspace/${segment(sessionId)}/upload")
        val fileResource = object : org.springframework.core.io.ByteArrayResource(fileBytes) {
            override fun getFilename(): String = fileName
        }
        val body = org.springframework.http.client.MultipartBodyBuilder()
        body.part("file", fileResource)
        body.part("path", path)

        return webClient.post()
            .uri(url)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .bodyValue(body.build())
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<Map<String, Any>>>() {})
            .awaitSingleOrNull()
            ?: throw emptyBody(url.toString())
    }

    /**
     * Download a file from workspace on agent-service.
     *
     * @return a Pair of (file bytes, content type string), or null when the agent answers with no
     *   body — the caller renders that as "no such file", which is what an empty response from a
     *   download endpoint means.
     */
    suspend fun workspaceDownload(
        baseUrl: String,
        sessionId: String,
        path: String,
    ): Pair<ByteArray, String>? {
        val url = agentUrl(
            baseUrl,
            "/api/agent/workspace/${segment(sessionId)}/download",
            mapOf("path" to path),
        )
        val response = webClient.get()
            .uri(url)
            .retrieve()
            .toEntity(ByteArray::class.java)
            .awaitSingleOrNull()
        val body = response?.body ?: return null
        return Pair(body, response.headers.contentType?.toString() ?: MediaType.APPLICATION_OCTET_STREAM_VALUE)
    }

    // ==================== Internal helpers ====================

    /**
     * Bound a stream from the moment it is subscribed: [streamIdleTimeoutSeconds] of silence between
     * events, or [streamMaxDurationMinutes] of wall clock, end it with a timeout. Either way the
     * caller sees a failed stream; what it must never see is a stream that simply stops.
     */
    internal fun Flux<ChatEvent>.withinStreamLimits(): Flux<ChatEvent> = timeout(Duration.ofSeconds(streamIdleTimeoutSeconds))
        .takeUntilOther(
            Mono.delay(Duration.ofMinutes(streamMaxDurationMinutes))
                .then(Mono.error(TimeoutException("stream ran past its $streamMaxDurationMinutes minute limit"))),
        )

    /**
     * An agent that answers 200 with no body has not answered. Reporting that as an empty result
     * would tell the caller the session simply has no history, no plan or no file.
     */
    private fun emptyBody(url: String): IllegalStateException = IllegalStateException("agent-service returned an empty body from $url")

    /**
     * Build a URL on the target instance.
     *
     * Two things the callers used to do by hand are done here: query values are percent-encoded (a
     * workspace path containing `#` or `=` used to be pasted raw into the query, where it truncated
     * the parameter or added one), and the instance's own scheme and port are kept (the builders this
     * replaces hardcoded `http`, which silently downgraded an https instance).
     */
    private fun agentUrl(
        baseUrl: String,
        path: String,
        query: Map<String, String> = emptyMap(),
    ): java.net.URI {
        val suffix = if (query.isEmpty()) {
            ""
        } else {
            query.entries.joinToString("&", prefix = "?") { (key, value) ->
                "${UriUtils.encodeQueryParam(key, StandardCharsets.UTF_8)}=" +
                    UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8)
            }
        }
        return java.net.URI.create(baseUrl.removeSuffix("/") + path + suffix)
    }

    /**
     * Percent-encode one path segment.
     *
     * Session ids are format-checked before they get this far, and the check rejects everything this
     * would encode — this is the second lock on the same door. The router calls agent-service with
     * its own service credentials, so an id carrying `/` or `#` would turn a request about one session
     * into a request against some other agent endpoint.
     */
    private fun segment(
        value: String,
    ): String = UriUtils.encodePathSegment(value, StandardCharsets.UTF_8)

    private fun logRequest(
        url: String,
        request: Any,
    ) {
        if (log.isDebugEnabled) {
            val json = objectMapper.writeValueAsString(request)
            log.debug("[Router→Agent] POST $url, body=$json")
        } else {
            log.info("[Router→Agent] POST $url, type=${request.javaClass.simpleName}")
        }
    }
}
