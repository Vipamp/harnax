package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.util.AesUtil
import com.agnetix.harnax.mapper.ApiKeyMapper
import com.agnetix.harnax.mapper.ChannelMapper
import com.agnetix.harnax.mapper.SessionMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Agent session refresh service.
 *
 * After an agent's configuration is updated, its cached instances in
 * agent-service (keyed by sessionId) keep serving the old spec until the
 * 30-minute cache TTL expires or a /refresh command arrives. This service
 * lets the admin console list the sessions related to an agent (channel
 * sessions + web sessions) and selectively push a REFRESH command for the
 * chosen ones via session-router, so the next message uses the new config.
 */
@Service
class AgentSessionRefreshService(
    private val channelMapper: ChannelMapper,
    private val sessionMapper: SessionMapper,
    private val apiKeyMapper: ApiKeyMapper,
    private val aesUtil: AesUtil,
    @Value("\${harnax.router.url:http://localhost:8081}")
    private val routerUrl: String,
) {
    private val log = LoggerFactory.getLogger(AgentSessionRefreshService::class.java)

    private val restClient: RestClient = RestClient.builder()
        .requestFactory(
            org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(30))
            },
        )
        .build()

    /**
     * List sessions related to an agent: channel-bound sessions and web sessions.
     */
    fun listRelatedSessions(agentId: Long): List<RelatedSessionInfo> {
        val result = mutableListOf<RelatedSessionInfo>()

        channelMapper.selectByAgentId(agentId).forEach { ch ->
            if (ch.sessionId.isNotBlank()) {
                result.add(
                    RelatedSessionInfo(
                        sessionId = ch.sessionId,
                        sourceType = "channel",
                        sourceName = "${ch.name} (${ch.type})",
                    ),
                )
            }
        }

        sessionMapper.selectByAgentId(agentId).forEach { s ->
            if (s.active == 1 && s.sessionId.isNotBlank()) {
                result.add(
                    RelatedSessionInfo(
                        sessionId = s.sessionId,
                        sourceType = "session",
                        sourceName = s.title.ifBlank { s.sessionId },
                    ),
                )
            }
        }
        return result
    }

    /**
     * Push a REFRESH command for each selected sessionId via session-router.
     * Returns per-session results; failures do not abort the batch.
     */
    fun refreshSessions(sessionIds: List<String>): List<SessionRefreshResult> {
        if (sessionIds.isEmpty()) return emptyList()
        val apiKey = resolveSystemApiKey()
            ?: return sessionIds.map { SessionRefreshResult(it, false, "No system API key available") }

        return sessionIds.map { sessionId ->
            try {
                val body = mapOf(
                    "sessionId" to sessionId,
                    "command" to "REFRESH",
                    "args" to "",
                    "type" to "COMMAND",
                )
                restClient.post()
                    .uri("$routerUrl/api/router/agent/command")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Api-Key", apiKey)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                log.info("Refresh command sent for session={}", sessionId)
                SessionRefreshResult(sessionId, true, null)
            } catch (e: Exception) {
                log.warn("Refresh command failed for session={}: {}", sessionId, e.message)
                SessionRefreshResult(sessionId, false, e.message)
            }
        }
    }

    /**
     * Reuse the channel-service SYSTEM key to authenticate against router,
     * matching how channel-service itself calls the same command endpoint.
     */
    private fun resolveSystemApiKey(): String? = try {
        apiKeyMapper.selectSystemKeyByServiceName("channel-service")
            ?.rawKeyEncrypted
            ?.let { aesUtil.decrypt(it) }
    } catch (e: Exception) {
        log.error("Failed to resolve system API key: {}", e.message)
        null
    }
}

data class RelatedSessionInfo(
    val sessionId: String,
    val sourceType: String, // "channel" | "session"
    val sourceName: String,
)

data class SessionRefreshResult(
    val sessionId: String,
    val success: Boolean,
    val error: String?,
)
