package com.agnetix.harnax.channel.service.client

import com.agnetix.harnax.agent.protocol.AgentRequest
import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatEvent
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.agent.protocol.CommandAgentRequest
import com.agnetix.harnax.agent.protocol.CommandResponse
import com.agnetix.harnax.agent.protocol.CommandType
import com.agnetix.harnax.agent.protocol.EndEventChatEvent
import com.agnetix.harnax.agent.protocol.ErrorChatEvent
import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.common.error.HarnaxErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
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

/**
 * Router Client.
 * Responsible for sending messages to the session-router
 * which then proxies them to the correct agent-service instance.
 */
@Service
class RouterClient(
    private val webClient: WebClient,
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val tokenProvider: InternalTokenProvider,
    @Value("\${router.service.url}") private val routerUrl: String,
) {

    private val log = LoggerFactory.getLogger(RouterClient::class.java)

    // TODO [P0] buildCurl() includes JWT auth headers and request body in INFO-level log output.
    //   This leaks credentials and user data to production log aggregators.
    //   Fix: remove auth headers from log output, or mask them; redact sensitive body fields.
    private fun buildCurl(url: String, body: String): String {
        val headers = tokenProvider.authHeaders()
        val headerArgs = headers.entries.joinToString(" ") { (k, v) -> "-H '$k: $v'" }
        return "curl -X POST '$url' -H 'Content-Type: application/json' $headerArgs -d '$body'"
    }

    /**
     * Send a chat message to the agent via the session-router (batch/non-streaming).
     * Uses RestClient (synchronous HTTP) wrapped in withContext(Dispatchers.IO).
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
        val json = objectMapper.writeValueAsString(request)
        log.info("[Channel→Router] {}", buildCurl(url, json))
        val startTime = System.currentTimeMillis()

        val resultVo: ResultVo<ChatResponse>? = try {
            withContext(Dispatchers.IO) {
                restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(object : ParameterizedTypeReference<ResultVo<ChatResponse>>() {})
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            log.error("[Channel←Router] Exception for session={}, elapsed={}ms: {}", sessionId, elapsed, e.message, e)
            null
        }

        val elapsed = System.currentTimeMillis() - startTime
        log.info("[Channel←Router] Response for session={}, isNull={}, elapsed={}ms", sessionId, resultVo == null, elapsed)

        if (resultVo == null) {
            return ChatResponse(sessionId = sessionId, content = "")
        }
        if (!resultVo.isSuccess()) {
            val errorMsg = resultVo.message ?: "Unknown router error"
            log.error("[Channel←Router] Router error for session={}: {}", sessionId, errorMsg)
            return ChatResponse(sessionId = sessionId, content = "[Router Error] $errorMsg")
        }
        val data = resultVo.data
        log.info("[Channel←Router] Chat response for session={}, contentLength={}", sessionId, data?.content?.length ?: 0)
        return data ?: ChatResponse(sessionId = sessionId, content = "")
    }

    /**
     * Send a command to the agent via the session-router (batch/non-streaming).
     */
    suspend fun sendCommand(sessionId: String, agentId: Long, command: CommandType, args: String = ""): CommandResponse {
        val request = CommandAgentRequest(sessionId = sessionId, command = command, args = args)

        val url = "$routerUrl/api/router/agent/command"
        val json = objectMapper.writeValueAsString(request)
        log.info("[Channel→Router] {}", buildCurl(url, json))

        val resultVo: ResultVo<CommandResponse>? = try {
            withContext(Dispatchers.IO) {
                restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})
            }
        } catch (e: Exception) {
            log.error("[Channel←Router] Command exception for session={}: {}", sessionId, e.message, e)
            null
        }

        if (resultVo == null) {
            return CommandResponse.failure(sessionId, "No response from agent-service")
        }
        if (!resultVo.isSuccess()) {
            val errorMsg = resultVo.message ?: "Unknown router error"
            log.error("[Channel←Router] Router error for command session={}: {}", sessionId, errorMsg)
            return CommandResponse.failure(sessionId, "[Router Error] $errorMsg")
        }
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
    fun streamRequest(request: AgentRequest, agentId: Long): kotlinx.coroutines.flow.Flow<ChatEvent> = when (request) {
        is ChatAgentRequest -> streamToAgent(
            sessionId = request.sessionId,
            message = request.message,
            imageUrls = request.imageUrls,
        )
        is CommandAgentRequest -> {
            // For commands in streaming context, send command and emit result as events
            kotlinx.coroutines.flow.flow {
                try {
                    val result = sendCommand(
                        sessionId = request.sessionId,
                        agentId = agentId,
                        command = request.command,
                    )
                    emit(
                        com.agnetix.harnax.agent.protocol.StreamTextChatEvent(
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
        }

        else -> {
            TODO()
        }
    }

    /**
     * Send a message to the agent via the session-router with SSE streaming.
     * Returns a Flow<ChatEvent> that can be collected.
     * @param sessionId Session identifier
     * @param agentId Agent ID (unused, kept for API compatibility)
     * @param message User message content
     * @return Flow of ChatEvent
     */
    fun streamToAgent(
        sessionId: String,
        message: String,
        imageUrls: List<String> = emptyList(),
    ): kotlinx.coroutines.flow.Flow<ChatEvent> {
        val request = ChatAgentRequest(
            sessionId = sessionId,
            message = message,
            imageUrls = imageUrls,
        )

        log.info("[Channel] Sending stream request to router for session={}, imageUrls={}", sessionId, imageUrls.size)

        return webClient.post()
            .uri("$routerUrl/api/router/agent/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(ChatEvent::class.java)
            .doOnNext { event ->
                log.info("[Channel←Router] Stream event received for session=$sessionId: ${event.javaClass.simpleName}")
            }
            .doOnComplete {
                log.info("[Channel←Router] Stream completed for session=$sessionId")
            }
            .onErrorResume { e ->
                val errorMsg = describeRouterError(e, sessionId)
                log.error("Stream connection to router failed for session=$sessionId: $errorMsg", e)
                Flux.just(
                    ErrorChatEvent(
                        code = HarnaxErrorCode.ROUTER_CONNECTION_ERROR.code,
                        message = errorMsg,
                    ),
                    EndEventChatEvent(),
                )
            }
            .asFlow()
    }

    /**
     * Build a descriptive error message for router call failures.
     */
    private fun describeRouterError(e: Throwable, sessionId: String): String {
        if (e is WebClientResponseException) {
            val status = e.statusCode.value()
            val target = "$routerUrl/api/router/agent/chat/stream"
            val body = e.responseBodyAsString.take(200)
            return when (status) {
                401 ->
                    "[Channel→Router] Authentication failed (401) calling $target for session=$sessionId. " +
                        "JWT may be invalid or expired. Response: $body"
                403 ->
                    "[Channel→Router] Forbidden (403) calling $target for session=$sessionId. " +
                        "Access denied. Response: $body"
                else -> "[Channel→Router] HTTP $status calling $target for session=$sessionId. Response: $body"
            }
        }
        return "[Channel→Router] Failed to reach router for session=$sessionId: ${e.message}"
    }
}
