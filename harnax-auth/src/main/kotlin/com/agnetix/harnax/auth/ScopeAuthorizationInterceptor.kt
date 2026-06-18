package com.agnetix.harnax.auth

import com.agnetix.harnax.common.dto.ResultVo
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import tools.jackson.databind.ObjectMapper

class ScopeAuthorizationInterceptor(
    private val objectMapper: ObjectMapper,
) : HandlerInterceptor {

    private val log = LoggerFactory.getLogger(ScopeAuthorizationInterceptor::class.java)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler !is HandlerMethod) return true

        val annotation = AnnotationUtils.findAnnotation(handler.method, RequireScope::class.java)
            ?: AnnotationUtils.findAnnotation(handler.beanType, RequireScope::class.java)
            ?: return true

        val context = AuthContextHolder.get()
        if (context == null) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required")
            return false
        }

        if (annotation.internalOnly && context.callerType != CallerType.INTERNAL_SERVICE) {
            log.warn("External caller '${context.callerId}' attempted internal-only endpoint: ${request.requestURI}")
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "This endpoint is restricted to internal services")
            return false
        }

        val requiredScopes = annotation.value
        if (requiredScopes.isNotEmpty() && !context.hasAnyScope(*requiredScopes)) {
            log.warn(
                "Caller '${context.callerId}' lacks required scope for ${request.requestURI}. " +
                    "Required: ${requiredScopes.toList()}, Has: ${context.scopes}",
            )
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "Insufficient scope. Required: ${requiredScopes.joinToString("|")}")
            return false
        }

        return true
    }

    private fun writeError(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json"
        response.writer.write(objectMapper.writeValueAsString(ResultVo.error<String>(status, message)))
    }
}
