package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.service.AgentRuntimeClient
import com.agnetix.harnax.auth.AuthRestTemplateInterceptor
import com.agnetix.harnax.auth.InternalTokenProvider
import com.agnetix.harnax.common.dto.ResultVo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriUtils
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Talks to session-router's clear-session proxy, which is the route that knows where a session lives.
 *
 * The router resolves the session id to the agent-service instance currently holding it (contract: an
 * unbound session is nothing to clear, so it answers success), then forwards one call there. Going to
 * agent-service directly instead would mean naming one instance from admin, and in more than one instance
 * the one that is asked is unlikely to be the one that holds the conversation, its plans and its container
 * — a clear that "succeeds" while the state survives.
 *
 * The bearer is the same internal token [SchedulerClientImpl] signs for the scheduler: the router's
 * `UnifiedAuthFilter` accepts a `typ=internal` token on `HARNAX_AUTH_SECRET` next to an API key, and an
 * internal caller is exactly what this is. It carries no user, so the router settles the request by its
 * own rule for read-only session paths and admin stays the one that decided the caller may touch this
 * session — the tenant check is [com.agnetix.harnax.admin.service.impl.SessionServiceImpl]'s, done before
 * the call goes out.
 */
@Service
class AgentRuntimeClientImpl(
    @Value("\${harnax.router.url:http://localhost:8081}") private val routerUrl: String,
    private val tokenProvider: InternalTokenProvider,
) : AgentRuntimeClient {

    private val log = LoggerFactory.getLogger(AgentRuntimeClientImpl::class.java)

    private val restClient: RestClient by lazy {
        val factory = org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(5))
            setReadTimeout(Duration.ofSeconds(30))
        }
        RestClient.builder()
            .requestFactory(factory)
            .requestInterceptor(AuthRestTemplateInterceptor(tokenProvider))
            .build()
    }

    override fun clearSession(sessionId: String): ResultVo<Void> {
        val uri = try {
            // One segment, encoded: an id that could carry a slash would otherwise add segments of its own
            // and put a different router endpoint on the wire. A stored `%` goes out as %25, so nothing
            // arrives already-encoded either.
            val segment = UriUtils.encodePathSegment(sessionId, StandardCharsets.UTF_8)
            URI.create("${routerUrl.trimEnd('/')}$CLEAR_SESSION_PATH$segment")
        } catch (e: Exception) {
            log.error("Cannot name the runtime target for session {}: {}", sessionId, e.message)
            return ResultVo.error("Invalid session id: ${e.message}")
        }
        return try {
            val result = restClient.delete()
                .uri(uri)
                .retrieve()
                // A refused teardown is still an answer from the router, and the one sentence the user has
                // to read is in its body: raising on the status would trade that for a transport error.
                .onStatus({ status -> status.isError }, { _, _ -> })
                .body(CLEAR_RESULT_TYPE)
            when {
                result == null -> ResultVo.error("No response from the session router")
                result.isSuccess() -> ResultVo.success()
                else -> ResultVo.error(result.code, result.message)
            }
        } catch (e: Exception) {
            log.error("Failed to clear session {} in the runtime: {}", sessionId, e.message)
            ResultVo.error("Session router unavailable: ${e.message}")
        }
    }

    companion object {
        /** Router's clear-session proxy, which resolves the session to the instance holding it. */
        private const val CLEAR_SESSION_PATH = "/api/router/agent/session/"

        private val CLEAR_RESULT_TYPE = object : ParameterizedTypeReference<ResultVo<String>>() {}
    }
}
