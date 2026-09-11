package com.agnetix.harnax.channel.service.client

import com.agnetix.harnax.agent.protocol.AgentRequest
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.protocol.ConfirmAgentRequest
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.agent.protocol.StreamTextChatEvent
import com.agnetix.harnax.channel.sdk.service.ReplyMarkers
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.common.error.HarnaxErrorCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * Router Client.
 * Responsible for sending messages to the session-router
 * which then proxies them to the correct agent-service instance.
 *
 * Calls are guarded by [RouterCircuitBreaker]: while router or agent-service is down, blocking
 * here for the full read timeout would otherwise tie up every channel worker and take healthy
 * channels down with the unhealthy dependency.
 *
 * The batch path intentionally does not hop to another dispatcher. RestClient is blocking, but
 * the callers are already [com.agnetix.harnax.channel.sdk.dispatch.ChannelTurnExecutor] workers —
 * a bounded, per-channel-limited pool. Bouncing to the shared `Dispatchers.IO` moved that bounded
 * load onto a JVM-wide 64-thread pool, which is how one slow agent turned into a global stall.
 */
@Service
class RouterClient(
    private val webClient: WebClient,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val breaker: RouterCircuitBreaker,
    @Value("\${router.service.url}") private val routerUrl: String,
    @Value("\${channel.proxy.stream-idle-timeout-ms:180000}") private val streamIdleTimeoutMs: Long,
) {

    private val log = LoggerFactory.getLogger(RouterClient::class.java)

    private fun buildCurl(url: String, body: String): String = "curl -X POST '$url' -H 'Content-Type: application/json' -d '$body'"

    /**
     * Send a chat message to the agent via the session-router (batch/non-streaming).
     */
    suspend fun sendToAgent(
        sessionId: String,
        agentId: Long,
        message: String,
        imageUrls: List<String> = emptyList(),
    ): ChatResponse {
        val request = ChatAgentRequest(
            sessionId = sessionId,
            message = message,
            imageUrls = imageUrls,
        )
        val url = "$routerUrl/api/router/agent/chat"

        if (!breaker.tryAcquire()) {
            return routerUnavailable(sessionId, "circuit open")
        }

        if (log.isDebugEnabled) {
            log.debug("[Channel→Router] {}", buildCurl(url, objectMapper.writeValueAsString(request)))
        }
        val startTime = System.currentTimeMillis()

        val resultVo: ResultVo<ChatResponse>? = try {
            restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<ChatResponse>>() {})
        } catch (e: Exception) {
            breaker.onFailure(e.message)
            log.error("[Channel←Router] Exception for session={}, elapsed={}ms: {}", sessionId, System.currentTimeMillis() - startTime, e.message, e)
            null
        }

        val elapsed = System.currentTimeMillis() - startTime
        if (resultVo == null) {
            log.error("[Channel←Router] No usable response for session={}, elapsed={}ms (router unreachable or agent timed out)", sessionId, elapsed)
            return routerUnavailable(sessionId, "no response from agent-service")
        }
        if (!resultVo.isSuccess()) {
            // The router answered — the call itself worked, so this is not a circuit failure.
            breaker.onSuccess()
            val errorMsg = resultVo.message ?: "Unknown router error"
            log.error("[Channel←Router] Router error for session={}: code={}, message={}", sessionId, resultVo.code, errorMsg)
            return ChatResponse(sessionId = sessionId, content = "${ReplyMarkers.ROUTER_ERROR_PREFIX} $errorMsg")
        }
        breaker.onSuccess()
        val data = resultVo.data
        log.info("[Channel←Router] Chat response for session={}, contentLength={}, attachments={}, elapsed={}ms", sessionId, data?.content?.length ?: 0, data?.attachments?.size ?: 0, elapsed)
        return data ?: routerUnavailable(sessionId, "empty response from agent-service")
    }

    /**
     * Send a command to the agent via the session-router (batch/non-streaming).
     */
    suspend fun sendCommand(
        sessionId: String,
        agentId: Long,
        command: CommandType,
        args: String = "",
    ): CommandResponse {
        val request = CommandAgentRequest(sessionId = sessionId, command = command, args = args)
        val url = "$routerUrl/api/router/agent/command"

        if (!breaker.tryAcquire()) {
            return CommandResponse.failure(sessionId, "Router circuit open — failing fast")
        }

        if (log.isDebugEnabled) {
            log.debug("[Channel→Router] {}", buildCurl(url, objectMapper.writeValueAsString(request)))
        }

        val resultVo: ResultVo<CommandResponse>? = try {
            restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})
        } catch (e: Exception) {
            breaker.onFailure(e.message)
            log.error("[Channel←Router] Command exception for session={}: {}", sessionId, e.message, e)
            null
        }

        if (resultVo == null) {
            return CommandResponse.failure(sessionId, "No response from agent-service")
        }
        if (!resultVo.isSuccess()) {
            breaker.onSuccess()
            val errorMsg = resultVo.message ?: "Unknown router error"
            log.error("[Channel←Router] Router error for command session={}: {}", sessionId, errorMsg)
            return CommandResponse.failure(sessionId, "${ReplyMarkers.ROUTER_ERROR_PREFIX} $errorMsg")
        }
        breaker.onSuccess()
        return resultVo.data ?: CommandResponse.failure(sessionId, "No response from agent-service")
    }

    /**
     * Send a parsed AgentRequest to the agent via session-router with SSE streaming.
     * Dispatches based on request type:
     * - ChatAgentRequest → streamToAgent()
     * - CommandAgentRequest → sendCommand() then emit result as events
     *
     * @param request Parsed AgentRequest
     * @param agentId Agent ID (unused, kept for API compatibility)
     * @return Flow of ChatEvent
     */
    fun streamRequest(
        request: AgentRequest,
        agentId: Long,
    ): Flow<ChatEvent> = when (request) {
        is ChatAgentRequest -> streamToAgent(
            sessionId = request.sessionId,
            message = request.message,
            imageUrls = request.imageUrls,
        )

        is CommandAgentRequest -> flow {
            // Commands are batch by nature: run them and emit the result as a single terminal event
            try {
                val result = sendCommand(
                    sessionId = request.sessionId,
                    agentId = agentId,
                    command = request.command,
                )
                emit(
                    StreamTextChatEvent(
                        message = result.message ?: "Command executed",
                        isLast = true,
                        tokenUsage = null,
                    ),
                )
                emit(EndEventChatEvent())
            } catch (e: Exception) {
                log.error("Command execution failed for session=${request.sessionId}", e)
                emit(
                    ErrorChatEvent(
                        code = HarnaxErrorCode.SYSTEM_ERROR.code,
                        message = e.message ?: "Command execution failed",
                    ),
                )
                emit(EndEventChatEvent())
            }
        }

        is ConfirmAgentRequest -> streamConfirm(request, agentId)

        else -> throw IllegalArgumentException("Unsupported request type: ${request::class.simpleName}")
    }

    /**
     * Send a message to the agent via the session-router with SSE streaming.
     * Returns a Flow<ChatEvent> that can be collected.
     *
     * @param sessionId Session identifier
     * @param agentId Agent ID (unused, kept for API compatibility)
     * @param message User message content
     * @return Flow of ChatEvent
     */
    fun streamToAgent(
        sessionId: String,
        message: String,
        imageUrls: List<String> = emptyList(),
    ): Flow<ChatEvent> {
        val request = ChatAgentRequest(
            sessionId = sessionId,
            message = message,
            imageUrls = imageUrls,
        )

        if (!breaker.tryAcquire()) {
            log.warn("[Channel→Router] Circuit open, skipping stream for session={}", sessionId)
            return failingStream(sessionId, "router circuit open — failing fast")
        }

        log.info("[Channel] Sending stream request to router for session={}, imageUrls={}", sessionId, imageUrls.size)

        return streamFrom(
            flux = webClient.post()
                .uri("$routerUrl/api/router/agent/chat/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(ChatEvent::class.java),
            sessionId = sessionId,
            label = "Stream",
        )
    }

    /**
     * Send a confirm request (HITL) to the agent via session-router with SSE streaming.
     * Used when the user responds to a tool confirmation prompt (/approve or /deny).
     */
    fun streamConfirm(
        request: ConfirmAgentRequest,
        agentId: Long,
    ): Flow<ChatEvent> {
        val url = "$routerUrl/api/router/agent/confirm"
        log.info("[Channel→Router] Sending confirm request for session={}, confirmed={}", request.sessionId, request.isConfirmed)

        if (!breaker.tryAcquire()) {
            log.warn("[Channel→Router] Circuit open, skipping confirm for session={}", request.sessionId)
            return failingStream(request.sessionId, "router circuit open — failing fast")
        }

        return streamFrom(
            flux = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(ChatEvent::class.java),
            sessionId = request.sessionId,
            label = "Confirm stream",
        )
    }

    /**
     * Shared SSE handling: idle timeout, breaker reporting, error translation.
     *
     * The idle timeout matters because a stream that stalls after its first event otherwise holds
     * its worker — and that conversation's serial lock — indefinitely: the HTTP read timeout does
     * not fire while the connection is still open and simply silent.
     */
    private fun streamFrom(
        flux: Flux<ChatEvent>,
        sessionId: String,
        label: String,
    ): Flow<ChatEvent> {
        var stream = flux
            .doOnNext { event ->
                if (log.isDebugEnabled) {
                    log.debug("[Channel←Router] {} event for session={}: {}", label, sessionId, event.javaClass.simpleName)
                }
            }
            .doOnComplete {
                breaker.onSuccess()
                log.debug("[Channel←Router] {} completed for session={}", label, sessionId)
            }
            .onErrorResume { e ->
                val errorMsg = describeRouterError(e, sessionId)
                breaker.onFailure(errorMsg)
                log.error("{} to router failed for session={}: {}", label, sessionId, errorMsg, e)
                Flux.just(
                    ErrorChatEvent(
                        code = HarnaxErrorCode.ROUTER_CONNECTION_ERROR.code,
                        message = errorMsg,
                    ),
                    EndEventChatEvent(),
                )
            }
        if (streamIdleTimeoutMs > 0) {
            stream = stream.timeout(Duration.ofMillis(streamIdleTimeoutMs))
                .onErrorResume(java.util.concurrent.TimeoutException::class.java) {
                    log.error("{} stalled with no event for {}ms, cancelling stream for session={}", label, streamIdleTimeoutMs, sessionId)
                    Flux.just(
                        ErrorChatEvent(
                            code = HarnaxErrorCode.ROUTER_CONNECTION_ERROR.code,
                            message = "AI response stream stalled (no event for ${streamIdleTimeoutMs}ms)",
                        ),
                        EndEventChatEvent(),
                    )
                }
        }
        return stream.asFlow()
    }

    private fun failingStream(
        sessionId: String,
        reason: String,
    ): Flow<ChatEvent> = flow {
        emit(
            ErrorChatEvent(
                code = HarnaxErrorCode.ROUTER_CONNECTION_ERROR.code,
                message = "Router unavailable: $reason",
            ),
        )
        emit(EndEventChatEvent())
    }

    private fun routerUnavailable(
        sessionId: String,
        reason: String,
    ): ChatResponse {
        val message = "${ReplyMarkers.ROUTER_ERROR_PREFIX} $reason"
        log.error("[Channel←Router] {} for session={}", message, sessionId)
        return ChatResponse(sessionId = sessionId, content = message)
    }

    /**
     * Build a descriptive error message for router call failures.
     */
    private fun describeRouterError(
        e: Throwable,
        sessionId: String,
    ): String {
        if (e is WebClientResponseException) {
            val status = e.statusCode.value()
            val target = "$routerUrl/api/router/agent/chat/stream"
            val body = e.responseBodyAsString.take(200)
            return when (status) {
                401 ->
                    "Authentication failed (401) calling $target for session=$sessionId. " +
                        "API key may be invalid or expired. Response: $body"

                403 ->
                    "Forbidden (403) calling $target for session=$sessionId. " +
                        "Access denied. Response: $body"

                else -> "HTTP $status calling $target for session=$sessionId. Response: $body"
            }
        }
        return "Failed to reach router for session=$sessionId: ${e.message}"
    }

    // ==================== Workspace ====================

    /**
     * Download a file from the sandbox workspace via router proxy.
     *
     * Not guarded by the circuit: a large file exceeding the read timeout says nothing about
     * whether chat works, and failing a download is already visible to the user.
     *
     * @param sessionId Session ID (used to locate the sandbox)
     * @param filePath  Absolute path inside the container (e.g. /workspace/output/report.pptx)
     * @return File bytes, or null if download failed
     */
    fun downloadWorkspaceFile(
        sessionId: String,
        filePath: String,
    ): ByteArray? {
        val uri = org.springframework.web.util.UriComponentsBuilder
            .fromUriString("$routerUrl/api/router/agent/workspace/$sessionId/download")
            .queryParam("path", filePath)
            .encode()
            .build()
            .toUri()
        return try {
            restClient.get()
                .uri(uri)
                .retrieve()
                .body(ByteArray::class.java)
        } catch (e: Exception) {
            log.error("[Channel←Router] Workspace download failed for session={}, path={}: {}", sessionId, filePath, e.message)
            null
        }
    }
}
