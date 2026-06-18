package com.agnetix.harnax.router.config

import com.agnetix.harnax.auth.AuthContextHolder
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

@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class ApiCallLogFilter(
    private val apiCallLogService: ApiCallLogService,
    private val sessionInfoClient: SessionInfoClient,
) : OncePerRequestFilter() {

    companion object {
        private val SESSION_ID_PATTERN = Regex("/agent/(?:chat|session)/(?:history/)?([^/]+)")
        private val REQUEST_TYPE_PATTERN = Regex("/agent/(chat|command|confirm)")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI
        if (isExcluded(path)) {
            filterChain.doFilter(request, response)
            return
        }

        val startTime = LocalDateTime.now()
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
            } catch (e: Exception) {
                // Never let logging break the request flow
            }

            wrappedResponse.copyBodyToResponse()
        }
    }

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
        val sessionId = extractSessionId(path)
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

    private fun extractSessionId(path: String): String? {
        // Match patterns like /agent/chat/{sessionId}, /agent/session/{sessionId}, /agent/chat/history/{sessionId}
        return SESSION_ID_PATTERN.find(path)?.groupValues?.get(1)
    }

    private fun extractRequestType(path: String): String? {
        return REQUEST_TYPE_PATTERN.find(path)?.groupValues?.get(1)?.uppercase()
    }

    private fun isExcluded(path: String): Boolean {
        return path.startsWith("/health") ||
            path.startsWith("/actuator") ||
            path.startsWith("/ai") ||
            path == "/api/router/health" ||
            path == "/api/router/metrics/cache"
    }
}
