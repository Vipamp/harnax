package com.agnetix.harnax.channel.service.client

import com.agnetix.harnax.agent.protocol.ChatEvent
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

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
     * @param agentId Agent ID
     * @param message User message content
     * @return Agent response content
     */
    suspend fun sendToAgent(sessionId: String, agentId: Long, message: String): String {
        val requestBody = mapOf("message" to message)

        log.debug("Sending sync request to router for session=$sessionId, agentId=$agentId")

        val response = webClient.post()
            .uri("$routerUrl/api/router/agent/chat?sessionId=$sessionId&agentId=$agentId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String::class.java)
            .awaitSingleOrNull()

        return response ?: ""
    }

    /**
     * Send a message to the agent via the session-router with SSE streaming.
     * Returns a Flow<ChatEvent> that can be collected.
     * Jackson polymorphism handles automatic JSON deserialization of ChatEvent subtypes.
     * @param sessionId Session identifier
     * @param agentId Agent ID
     * @param message User message content
     * @return Flow of ChatEvent
     */
    fun streamToAgent(sessionId: String, agentId: Long, message: String): kotlinx.coroutines.flow.Flow<ChatEvent> {
        val requestBody = mapOf("message" to message)

        log.debug("Sending stream request to router for session=$sessionId, agentId=$agentId")

        return webClient.post()
            .uri("$routerUrl/api/router/agent/chat/stream?sessionId=$sessionId&agentId=$agentId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToFlux(ChatEvent::class.java)
            .asFlow()
    }
}
