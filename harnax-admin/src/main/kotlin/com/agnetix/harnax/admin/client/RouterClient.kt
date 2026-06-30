package com.agnetix.harnax.admin.client

import com.agnetix.harnax.agent.protocol.ChatAgentRequest
import com.agnetix.harnax.agent.protocol.ChatResponse
import com.agnetix.harnax.common.dto.ResultVo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

@Component
class RouterClient(
    @Value("\${harnax.router.url:http://localhost:8081}") private val routerUrl: String,
    @Value("\${agent-task.api-key:}") private val apiKey: String,
    @Value("\${agent-task.timeout-seconds:300}") private val timeoutSeconds: Int,
) {
    private val log = LoggerFactory.getLogger(RouterClient::class.java)

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

        log.info("[Admin→Router] POST {} - sessionId: {}", url, sessionId)
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
            log.error("[Admin←Router] Exception for session={}, elapsed={}ms: {}", sessionId, elapsed, e.message, e)
            throw RuntimeException("Failed to call router: ${e.message}", e)
        }

        val elapsed = System.currentTimeMillis() - startTime

        if (resultVo == null || resultVo.code != 200 || resultVo.data == null) {
            val errorMsg = resultVo?.message ?: "No response from router"
            log.error("[Admin←Router] Error for session={}, elapsed={}ms: {}", sessionId, elapsed, errorMsg)
            throw RuntimeException("Router returned error: $errorMsg")
        }

        log.info("[Admin←Router] Success for session={}, elapsed={}ms", sessionId, elapsed)
        return resultVo.data!!
    }
}
