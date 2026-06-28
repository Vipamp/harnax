package com.agnetix.harnax.admin.config

import com.agnetix.harnax.common.dto.ResultVo
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/**
 * Simple authentication filter for internal API endpoints (/api/admin/internal/).
 *
 * Validates requests from other harnax services (channel, router, agent-service)
 * using a shared secret via `Authorization: Bearer <secret>` header.
 */
@Component
class InternalApiAuthFilter(
    private val objectMapper: ObjectMapper,
    @Value("\${admin.internal-api.secret:}") private val secret: String,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(InternalApiAuthFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return !path.startsWith(INTERNAL_API_PREFIX)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (secret.isBlank()) {
            log.warn("Internal API secret is not configured, rejecting request to {}", request.requestURI)
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Internal API is not configured")
            return
        }

        val authHeader = request.getHeader("Authorization")
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            val token = authHeader.removePrefix("Bearer ").trim()
            if (token == secret) {
                filterChain.doFilter(request, response)
                return
            }
            val tokenPreview = token.take(12) + "..."
            val secretPreview = secret.take(6) + "..."
            val isLikelyJwt = token.count { it == '.' } == 2
            val hint = if (isLikelyJwt) {
                " Received a JWT token instead of raw shared secret. " +
                    "Caller should send admin.internal-api.secret value directly, not a JWT."
            } else {
                " Expected prefix: $secretPreview, received prefix: $tokenPreview"
            }
            log.warn(
                "[InternalApiAuth] Invalid credentials for ${request.requestURI} from ${request.remoteAddr}.$hint",
            )
            writeError(
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                "[InternalApiAuth] Invalid credentials for ${request.requestURI}.$hint",
            )
            return
        }

        val hasAuthHeader = authHeader != null
        log.warn(
            "[InternalApiAuth] Missing Bearer token for ${request.requestURI} from ${request.remoteAddr} " +
                "(hasAuthHeader=$hasAuthHeader)",
        )
        writeError(
            response,
            HttpServletResponse.SC_UNAUTHORIZED,
            "[InternalApiAuth] Missing or malformed Authorization header for ${request.requestURI}. " +
                "Expected: Authorization: Bearer <admin-internal-api-secret>",
        )
    }

    private fun writeError(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write(objectMapper.writeValueAsString(ResultVo.error<String>(status, message)))
    }

    companion object {
        private const val INTERNAL_API_PREFIX = "/api/admin/internal"
    }
}
