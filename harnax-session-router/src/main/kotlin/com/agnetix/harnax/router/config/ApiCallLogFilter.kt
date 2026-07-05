package com.agnetix.harnax.router.config

import com.agnetix.harnax.auth.AuthContextHolder
import com.agnetix.harnax.router.controller.AgentProxyController
import com.agnetix.harnax.router.service.ApiCallLogService
import com.agnetix.harnax.router.service.SessionInfoClient
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.time.LocalDateTime

/**
 * Records API call logs for all requests under `/api/router/agent/`.
 *
 * For normal (non-SSE) endpoints the response is wrapped with [ContentCachingResponseWrapper]
 * so the status code is available after the controller has written the body.
 *
 * SSE endpoints (`/stream` suffix or `/confirm`) must NOT be wrapped — buffering the
 * entire response body in memory prevents SSE events from being flushed in real time.
 * For those endpoints we record the start/end time and the HTTP status set by the
 * controller on the raw response.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
class ApiCallLogFilter(
    private val apiCallLogService: ApiCallLogService,
    private val sessionInfoClient: SessionInfoClient,
) : OncePerRequestFilter() {

    companion object {
        // Extracts sessionId from URL paths like /agent/chat/history/{id}, /agent/session/{id}
        private val SESSION_ID_URL_PATTERN = Regex("/agent/(?:chat|session)/(?:history/)?([^/]+)")
        private val REQUEST_TYPE_PATTERN = Regex("/agent/(chat|command|confirm)")

        // Only record logs for calls under /api/router/agent/ (all sub-paths).
        private const val LOG_PATH_PREFIX = "/api/router/agent/"

        // SSE endpoints that must NOT use ContentCachingResponseWrapper,
        // because buffering the response body breaks the event stream.
        private const val SSE_PATH_SUFFIX = "/stream"
        private val SSE_EXACT_PATHS = setOf("/api/router/agent/confirm")

        // Suspend-fun batch endpoints — incompatible with ContentCachingResponseWrapper.
        // Spring MVC async dispatch for Kotlin suspend controllers returns from the filter
        // chain before the coroutine writes the response body, making the wrapper buffer
        // an empty body and flush it prematurely.
        private val SUSPEND_ENDPOINTS = setOf(
            "/api/router/agent/chat",
            "/api/router/agent/command",
            "/api/router/agent/session",
            "/api/router/agent/chat/history",
            "/api/router/agent/workspace",
        )
    }

    public override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI
        if (!path.startsWith(LOG_PATH_PREFIX)) {
            filterChain.doFilter(request, response)
            return
        }

        val startTime = LocalDateTime.now()

        if (isSseEndpoint(path)) {
            // SSE: no response wrapper, log after the stream finishes (or the client disconnects).
            var errorMessage: String? = null
            try {
                filterChain.doFilter(request, response)
            } catch (e: Exception) {
                errorMessage = e.message
                throw e
            } finally {
                val endTime = LocalDateTime.now()
                val statusCode = response.status
                val success = statusCode in 200..299 && errorMessage == null
                try {
                    recordLog(request, statusCode, success, errorMessage, startTime, endTime)
                } catch (_: Exception) {
                    // Never let logging break the request flow
                }
            }
        } else if (isSuspendEndpoint(path)) {
            // Suspend-fun (batch) endpoints: ContentCachingResponseWrapper is incompatible
            // with Spring MVC's async dispatch for Kotlin suspend controllers — the filter
            // chain returns before the coroutine writes the body, so copyBodyToResponse()
            // flushes an empty buffer.  Skip the wrapper; read status directly from response.
            var errorMessage: String? = null
            try {
                filterChain.doFilter(request, response)
            } catch (e: Exception) {
                errorMessage = e.message
                throw e
            } finally {
                val endTime = LocalDateTime.now()
                val statusCode = response.status
                val success = statusCode in 200..299 && errorMessage == null
                try {
                    recordLog(request, statusCode, success, errorMessage, startTime, endTime)
                } catch (_: Exception) {
                    // Never let logging break the request flow
                }
            }
        } else {
            // Non-SSE: wrap response to capture status after controller writes the body.
            val wrappedResponse = ContentCachingResponseWrapper(response)
            var errorMessage: String? = null
            try {
                filterChain.doFilter(request, wrappedResponse)
            } catch (e: Exception) {
                errorMessage = e.message
                throw e
            } finally {
                val endTime = LocalDateTime.now()
                val statusCode = wrappedResponse.status
                val success = statusCode in 200..299 && errorMessage == null
                try {
                    recordLog(request, statusCode, success, errorMessage, startTime, endTime)
                } catch (_: Exception) {
                    // Never let logging break the request flow
                }
                wrappedResponse.copyBodyToResponse()
            }
        }
    }

    private fun isSseEndpoint(path: String): Boolean = path in SSE_EXACT_PATHS || path.endsWith(SSE_PATH_SUFFIX)

    /**
     * Suspend-fun batch endpoints that must NOT use ContentCachingResponseWrapper.
     * Spring MVC async dispatch for Kotlin suspend controllers returns from the filter
     * chain before the coroutine writes the response body, making the wrapper incompatible.
     */
    private fun isSuspendEndpoint(path: String): Boolean = SUSPEND_ENDPOINTS.any { path == it || path.startsWith("$it/") }

    private fun recordLog(
        request: HttpServletRequest,
        statusCode: Int,
        success: Boolean,
        errorMessage: String?,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
    ) {
        val context = AuthContextHolder.get()
        val callerId = context?.callerId ?: "unknown"
        val callerType = context?.callerType?.name ?: "UNKNOWN"
        val tenantId = context?.tenantId

        val path = request.requestURI
        // Primary: read from request attribute set by the controller (most reliable).
        // Fallback: extract from URL path (GET/DELETE endpoints with {sessionId} in path).
        val sessionId =
            request.getAttribute(AgentProxyController.SESSION_ID_ATTR) as? String
                ?: extractSessionIdFromPath(path)
        val requestType = extractRequestType(path)

        var agentId: Long? = null
        var agentName: String? = null
        var modelId: Long? = null
        var modelName: String? = null

        if (sessionId != null) {
            val sessionInfo = sessionInfoClient.getSessionInfo(sessionId)
            if (sessionInfo != null) {
                agentId = sessionInfo.agentId
                agentName = sessionInfo.agentName
                modelId = sessionInfo.modelId
                modelName = sessionInfo.modelName
            }
        }

        val instanceId = MDC.get("instanceId")
        val requestId = MDC.get("requestId") ?: request.getHeader("X-Request-Id")

        val entry = apiCallLogService.buildLogEntry(
            callerId = callerId,
            callerType = callerType,
            tenantId = tenantId,
            sessionId = sessionId,
            agentId = agentId,
            agentName = agentName,
            modelId = modelId,
            modelName = modelName,
            endpoint = path,
            method = request.method,
            requestType = requestType,
            statusCode = statusCode,
            success = success,
            errorMessage = errorMessage,
            startTime = startTime,
            endTime = endTime,
            instanceId = instanceId,
            requestId = requestId,
        )

        apiCallLogService.record(entry)
    }

    /**
     * Extract sessionId from URL path (e.g. /agent/chat/history/{id}, /agent/session/{id}).
     */
    private fun extractSessionIdFromPath(path: String): String? = SESSION_ID_URL_PATTERN.find(path)?.groupValues?.get(1)

    private fun extractRequestType(path: String): String? = REQUEST_TYPE_PATTERN.find(path)?.groupValues?.get(1)?.uppercase()
}
