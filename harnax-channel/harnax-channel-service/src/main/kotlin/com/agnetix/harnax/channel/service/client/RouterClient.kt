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
     * Send a message synchronously to the agent via the session-router.
     * @param sessionId Session identifier
     * @param agentId Agent ID (unused, kept for API compatibility)
     * @param message User message content
     * @return ChatResponse with aggregated content
     */
    suspend fun sendToAgent(sessionId: String, agentId: Long, message: String): ChatResponse {
        val request = ChatAgentRequest(sessionId = sessionId, message = message)

        log.debug("Sending sync request to router for session=$sessionId")

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
        is ChatAgentRequest -> streamToAgent(sessionId = request.sessionId, message = request.message)
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
    }

    /**
     * Send a message to the agent via the session-router with SSE streaming.
     * Returns a Flow<ChatEvent> that can be collected.
     * @param sessionId Session identifier
     * @param agentId Agent ID (unused, kept for API compatibility)
     * @param message User message content
     * @return Flow of ChatEvent
     */
    fun streamToAgent(sessionId: String, message: String): kotlinx.coroutines.flow.Flow<ChatEvent> {
        val request = ChatAgentRequest(sessionId = sessionId, message = message)

        log.debug("Sending stream request to router for session=$sessionId")

        return webClient.post()
            .uri("$routerUrl/api/router/agent/chat/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(ChatEvent::class.java)
            .onErrorResume { e ->
                log.error("Stream connection to router failed for session=$sessionId", e)
                Flux.just(
                    ErrorChatEvent(
                        code = HarnaxErrorCode.ROUTER_CONNECTION_ERROR.code,
                        message = e.message ?: "Connection failed",
                    ),
                    EndEventChatEvent(),
                )
            }
            .asFlow()
    }
}
