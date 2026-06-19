package com.agnetix.harnax.auth

import com.agnetix.harnax.common.dto.ResultVo
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import tools.jackson.databind.ObjectMapper

/**
 * Interceptor that enforces [InternalOnly] annotation.
 * Endpoints marked with [InternalOnly] reject external API callers (403 Forbidden).
 * All authenticated callers (both internal and external) can access unmarked endpoints.
 */
class InternalAuthorizationInterceptor(
    private val objectMapper: ObjectMapper,
) : HandlerInterceptor {

    private val log = LoggerFactory.getLogger(InternalAuthorizationInterceptor::class.java)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        // Skip for async dispatches (e.g., SSE streaming async processing).
        // AuthContext is set by UnifiedAuthFilter only on the initial REQUEST dispatch;
        // re-checking on async dispatches would fail because AuthContextHolder is ThreadLocal-based.
        if (request.dispatcherType != DispatcherType.REQUEST) {
            return true
        }

        if (handler !is HandlerMethod) return true

        val annotation = AnnotationUtils.findAnnotation(handler.method, InternalOnly::class.java)
            ?: AnnotationUtils.findAnnotation(handler.beanType, InternalOnly::class.java)

        if (annotation == null) return true

        val context = AuthContextHolder.get()
        if (context == null) {
            log.warn("[InternalAuth] No AuthContext for ${request.requestURI} from ${request.remoteAddr} — UnifiedAuthFilter may have skipped this path")
            writeError(
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "[InternalAuthorization] Authentication required for ${request.requestURI}. " +
                    "AuthContext is missing — check UnifiedAuthFilter configuration.",
            )
            return false
        }

        if (context.callerType != CallerType.INTERNAL_SERVICE) {
            log.warn("External caller '${context.callerId}' attempted internal-only endpoint: ${request.requestURI}")
            writeError(
                response,
                HttpServletResponse.SC_FORBIDDEN,
                "[InternalAuthorization] Endpoint ${request.requestURI} is restricted to internal services. " +
                    "Caller '${context.callerId}' is ${context.callerType}.",
            )
            return false
        }

        return true
    }

    private fun writeError(response: HttpServletResponse, status: Int, message: String) {
        // Skip if response is already committed (e.g., SSE stream has started writing)
        if (response.isCommitted) {
            log.warn("[InternalAuth] Cannot write error: response already committed for status=$status")
            return
        }
        response.status = status
        response.contentType = "application/json"
        try {
            response.outputStream.write(objectMapper.writeValueAsString(ResultVo.error<String>(status, message)).toByteArray())
        } catch (e: IllegalStateException) {
            // Fallback: if outputStream was already obtained, try writer
            response.writer.write(objectMapper.writeValueAsString(ResultVo.error<String>(status, message)))
        }
    }
}
