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
import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.common.error.HarnaxErrorCode
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux

/**
 * Router Client.
 * Responsible for sending messages to the session-router
 * which then proxies them to the correct agent-service instance.
 */
@Service
class RouterClient(
    private val webClient: WebClient,
    @Value("\${router.service.url}") private val routerUrl: String,
) {

    private val log = LoggerFactory.getLogger(RouterClient::class.java)

    /**
     * Send a chat message synchronously to the agent via the session-router.
     * Supports imageUrls for multimodal input.
     *
     * @param sessionId Session identifier
     * @param agentId Agent ID (unused, kept for API compatibility)
     * @param message User message content
     * @param imageUrls Image URLs or base64 data URLs for multimodal input
     * @return ChatResponse with aggregated content
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

        log.debug("Sending sync request to router for session={}, images={}", sessionId, imageUrls.size)

        val resultVo = webClient.post()
            .uri("$routerUrl/api/router/agent/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<ChatResponse>>() {})
            .awaitSingleOrNull()

        return resultVo?.data ?: ChatResponse(sessionId = sessionId, content = "")
    }

    /**
     * Send a command to the agent via the session-router.
     * @param sessionId Session identifier
     * @param agentId Agent ID (unused, kept for API compatibility)
     * @param command Command payload
     * @return CommandResponse with execution result
     */
    suspend fun sendCommand(sessionId: String, agentId: Long, command: CommandType): CommandResponse {
        val request = CommandAgentRequest(sessionId = sessionId, command = command)

        log.debug("Sending command request to router for session=$sessionId")

        val resultVo = webClient.post()
            .uri("$routerUrl/api/router/agent/command")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<ResultVo<CommandResponse>>() {})
            .awaitSingleOrNull()

        return resultVo?.data ?: CommandResponse.failure(sessionId, "No response from agent-service")
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
