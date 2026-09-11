package com.agnetix.harnax.router.config

import com.agnetix.harnax.auth.AuthContextHolder
import com.agnetix.harnax.router.controller.AgentProxyController
import com.agnetix.harnax.router.proxy.SessionRouterService
import com.agnetix.harnax.router.service.ApiCallLogService
import com.agnetix.harnax.router.service.SessionInfoClient
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records API call logs for all requests under `/api/router/agent/`.
 *
 * The row is written when the response is finished. Everything the log needs that is not the final
 * status — the caller, the session, the agent behind it, the instance the router picked — is resolved
 * on the thread that served the request, because the thread the response finishes on (an agent's
 * event loop for a stream, a coroutine dispatcher for a suspend call) carries none of them.
 *
 * SSE endpoints (`/stream` suffix or `/confirm`) are not wrapped with [ContentCachingResponseWrapper]:
 * buffering a stream in memory stops the events from reaching the client. The same applies to the
 * suspend endpoints listed in [SUSPEND_ENDPOINTS] — their filter chain unwinds while the response is
 * still being produced, so a wrapper would flush an empty body.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
class ApiCallLogFilter(
    private val apiCallLogService: ApiCallLogService,
    private val sessionInfoClient: SessionInfoClient,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(ApiCallLogFilter::class.java)

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

    /**
     * What a call costs and whether it worked is decided by the response, not by the filter chain:
     * for a stream or a suspend call the chain returns as soon as async processing starts, minutes
     * before the agent falls over. Logging there recorded 200 in ~0ms for the endpoints that fail
     * most often, which is the opposite of why the table exists.
     */
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
        // The wrapper is for calls that are over when the chain returns. Wrapping the calls that are
        // still running would hold their body in memory — which is what breaks a stream.
        val buffered = !isSseEndpoint(path) && !isSuspendEndpoint(path)
        val responseToLog = if (buffered) ContentCachingResponseWrapper(response) else response

        var errorMessage: String? = null
        try {
            filterChain.doFilter(request, responseToLog)
        } catch (e: Exception) {
            errorMessage = e.message
            throw e
        } finally {
            try {
                val pending = snapshot(request, responseToLog, path, startTime, errorMessage)
                if (!buffered && request.isAsyncStarted) logWhenSettled(pending) else write(pending)
            } catch (e: Exception) {
                // Logging must never break the request flow, and must never mask its outcome either.
                log.debug("Could not log the call to $path: ${e.message}")
            }
            if (responseToLog is ContentCachingResponseWrapper) {
                runCatching { responseToLog.copyBodyToResponse() }
            }
        }
    }

    private fun isSseEndpoint(path: String): Boolean = path in SSE_EXACT_PATHS || path.endsWith(SSE_PATH_SUFFIX)

    /**
     * Suspend-fun batch endpoints that must NOT use ContentCachingResponseWrapper.
     * Spring MVC async dispatch for Kotlin suspend controllers returns from the filter
     * chain before the coroutine writes the body, making the wrapper incompatible.
     */
    private fun isSuspendEndpoint(path: String): Boolean = SUSPEND_ENDPOINTS.any { path == it || path.startsWith("$it/") }

    /**
     * Resolve everything that belongs to the serving thread while still on it: the auth context and
     * MDC are thread-locals, and the session lookup is a blocking call that has no business running
     * on an agent's event loop.
     */
    private fun snapshot(
        request: HttpServletRequest,
        response: HttpServletResponse,
        path: String,
        startTime: LocalDateTime,
        errorMessage: String?,
    ): PendingCallLog {
        val context = AuthContextHolder.get()

        // Primary: read from the request attribute set by the controller (most reliable).
        // Fallback: extract from the URL, which is all a GET/DELETE with {sessionId} in it offers.
        val sessionId = request.getAttribute(AgentProxyController.SESSION_ID_ATTR) as? String
            ?: extractSessionIdFromPath(path)

        // The router sets MDC on the thread that places the call and clears it on the way out, so for
        // an async call the placement is read off the request attribute it recorded instead.
        val instanceId = request.getAttribute(SessionRouterService.ROUTED_INSTANCE_ATTR) as? String
            ?: MDC.get("instanceId")
        val requestId = MDC.get("requestId") ?: request.getHeader("X-Request-Id")

        val sessionInfo = sessionId?.let { lookupSessionInfo(it) }
        return PendingCallLog(
            request = request,
            response = response,
            startTime = startTime,
            path = path,
            method = request.method,
            callerId = context?.callerId ?: "unknown",
            callerType = context?.callerType?.name ?: "UNKNOWN",
            tenantId = context?.tenantId,
            sessionId = sessionId,
            agentId = sessionInfo?.agentId,
            agentName = sessionInfo?.agentName,
            modelId = sessionInfo?.modelId,
            modelName = sessionInfo?.modelName,
            instanceId = instanceId,
            requestId = requestId,
            requestType = extractRequestType(path),
            errorMessage = errorMessage,
        )
    }

    /**
     * A session's agent is worth a failed call log too, so an admin that cannot answer costs the four
     * enrichment columns and nothing else.
     */
    private fun lookupSessionInfo(sessionId: String) = try {
        sessionInfoClient.getSessionInfo(sessionId)
    } catch (e: Exception) {
        log.debug("Could not enrich the call log of session $sessionId: ${e.message}")
        null
    }

    private fun logWhenSettled(pending: PendingCallLog) {
        val asyncContext = try {
            pending.request.asyncContext
        } catch (e: IllegalStateException) {
            // The response was already written when we asked: this is the last moment it can be read.
            write(pending)
            return
        }
        val written = AtomicBoolean(false)
        try {
            asyncContext.addListener(
                object : AsyncListener {
                    // A restarted async request is still the same call awaiting its one log row.
                    override fun onStartAsync(event: AsyncEvent) {
                        Unit
                    }

                    override fun onComplete(event: AsyncEvent) {
                        if (written.compareAndSet(false, true)) write(pending)
                    }

                    override fun onTimeout(event: AsyncEvent) {
                        if (!written.compareAndSet(false, true)) return
                        pending.timedOut = true
                        write(pending)
                    }

                    override fun onError(event: AsyncEvent) {
                        if (written.compareAndSet(false, true)) write(pending)
                    }
                },
            )
        } catch (e: IllegalStateException) {
            if (written.compareAndSet(false, true)) write(pending)
        }
    }

    private fun write(pending: PendingCallLog) {
        try {
            val statusCode = pending.response.status
            val success = statusCode in 200..299 && pending.errorMessage == null && !pending.timedOut
            val entry = apiCallLogService.buildLogEntry(
                callerId = pending.callerId,
                callerType = pending.callerType,
                tenantId = pending.tenantId,
                sessionId = pending.sessionId,
                agentId = pending.agentId,
                agentName = pending.agentName,
                modelId = pending.modelId,
                modelName = pending.modelName,
                endpoint = pending.path,
                method = pending.method,
                requestType = pending.requestType,
                statusCode = statusCode,
                success = success,
                errorMessage = pending.errorMessage ?: pending.timedOutMessage(),
                startTime = pending.startTime,
                endTime = LocalDateTime.now(),
                instanceId = pending.instanceId,
                requestId = pending.requestId,
            )
            apiCallLogService.record(entry)
        } catch (e: Exception) {
            // Never let logging break the request flow
            log.debug("Could not record the call log for ${pending.path}: ${e.message}")
        }
    }

    private fun extractSessionIdFromPath(path: String): String? = SESSION_ID_URL_PATTERN.find(path)?.groupValues?.get(1)

    private fun extractRequestType(path: String): String? = REQUEST_TYPE_PATTERN.find(path)?.groupValues?.get(1)?.uppercase()

    /**
     * A call that is still running when its async request times out produced nothing for the client,
     * whatever the container managed to write into the response.
     */
    private fun PendingCallLog.timedOutMessage(): String? = if (timedOut) "Request timed out before the response was written" else null

    private class PendingCallLog(
        val request: HttpServletRequest,
        val response: HttpServletResponse,
        val startTime: LocalDateTime,
        val path: String,
        val method: String,
        val callerId: String,
        val callerType: String,
        val tenantId: Long?,
        val sessionId: String?,
        val agentId: Long?,
        val agentName: String?,
        val modelId: Long?,
        val modelName: String?,
        val instanceId: String?,
        val requestId: String?,
        val requestType: String?,
        val errorMessage: String?,
    ) {
        @Volatile
        var timedOut: Boolean = false
    }
}
