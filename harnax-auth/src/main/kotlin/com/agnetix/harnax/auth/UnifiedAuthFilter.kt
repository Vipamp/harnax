package com.agnetix.harnax.auth

import com.agnetix.harnax.common.dto.ResultVo
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class UnifiedAuthFilter(
    private val tokenProvider: InternalTokenProvider,
    private val enabled: Boolean,
    private val objectMapper: ObjectMapper,
    private val externalApiKeyValidator: ExternalApiKeyValidator? = null,
    private val extraSkipPaths: List<String> = emptyList(),
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(UnifiedAuthFilter::class.java)

    private val unprotectedPrefixes = listOf(
        "/health",
        "/actuator",
        "/ai",
    ) + extraSkipPaths

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!enabled) {
            filterChain.doFilter(request, response)
            return
        }

        val path = request.requestURI
        if (unprotectedPrefixes.any { path.startsWith(it) }) {
            filterChain.doFilter(request, response)
            return
        }

        val authHeader = request.getHeader("Authorization")
        val callerId = request.getHeader("X-Caller-Id") ?: "unknown"
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.removePrefix("Bearer ").trim()
            val tokenPreview = token.take(20) + "..."
            try {
                val context = tokenProvider.verifyToken(token)
                AuthContextHolder.set(context)
                try {
                    filterChain.doFilter(request, response)
                } finally {
                    AuthContextHolder.clear()
                }
                return
            } catch (e: Exception) {
                log.warn(
                    "JWT validation failed for $path from ${request.remoteAddr} " +
                        "(caller=$callerId, token=$tokenPreview): ${e.javaClass.simpleName}: ${e.message}",
                )
                writeError(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "[UnifiedAuthFilter] JWT validation failed for $path: ${e.message}. " +
                        "Check that both services use the same harnax.auth.internal.shared-secret.",
                )
                return
            }
        }

        val apiKey = request.getHeader("X-Api-Key")
        if (apiKey != null && externalApiKeyValidator != null) {
            try {
                val context = externalApiKeyValidator.validate(apiKey)
                AuthContextHolder.set(context)
                try {
                    filterChain.doFilter(request, response)
                } finally {
                    AuthContextHolder.clear()
                }
                return
            } catch (e: SecurityException) {
                log.warn("Invalid API key for $path from ${request.remoteAddr}: ${e.message}")
                writeError(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "[UnifiedAuthFilter] API key validation failed for $path: ${e.message}",
                )
                return
            }
        }

        val hasAuthHeader = authHeader != null
        val hasApiKey = apiKey != null
        log.warn("Missing credentials for $path from ${request.remoteAddr} (authHeader=$hasAuthHeader, apiKey=$hasApiKey)")
        writeError(
            response,
            HttpServletResponse.SC_UNAUTHORIZED,
            "[UnifiedAuthFilter] No valid credentials for $path. " +
                "Provide a Bearer JWT (Authorization header) or X-Api-Key header.",
        )
    }

    private fun writeError(response: HttpServletResponse, status: Int, message: String) {
        if (response.isCommitted) {
            log.warn("[UnifiedAuthFilter] Cannot write error: response already committed")
            return
        }
        response.status = status
        response.contentType = "application/json"
        try {
            response.outputStream.write(objectMapper.writeValueAsString(ResultVo.error<String>(status, message)).toByteArray())
        } catch (e: IllegalStateException) {
            response.writer.write(objectMapper.writeValueAsString(ResultVo.error<String>(status, message)))
        }
    }
}
