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
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.removePrefix("Bearer ").trim()
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
                log.warn("Invalid internal JWT for $path from ${request.remoteAddr}: ${e.message}")
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired internal token")
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
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, e.message ?: "Invalid API key")
                return
            }
        }

        log.warn("Missing credentials for $path from ${request.remoteAddr}")
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid credentials")
    }

    private fun writeError(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json"
        response.writer.write(objectMapper.writeValueAsString(ResultVo.error<String>(status, message)))
    }
}
