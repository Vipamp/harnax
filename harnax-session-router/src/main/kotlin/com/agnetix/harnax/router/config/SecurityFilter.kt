package com.agnetix.harnax.router.config

import com.agnetix.harnax.common.dto.ResultVo
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class SecurityFilter(
    @Value($$"${router.security.api-key:}")
    private val apiKey: String,
    @Value($$"${router.security.enabled:false}")
    private val securityEnabled: Boolean,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(SecurityFilter::class.java)

    private val instanceEndpoints = setOf(
        "/api/router/instance/register",
        "/api/router/instance/heartbeat",
        "/api/router/instance/unregister",
        "/api/router/instance/drain",
    )

    private val paramPattern = Regex("^[a-zA-Z0-9._-]+$")
    private val hostPattern = Regex("^[a-zA-Z0-9._-]+$")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI

        if (securityEnabled && path in instanceEndpoints) {
            val providedKey = request.getHeader("X-Api-Key")
            if (providedKey != apiKey || apiKey.isBlank()) {
                log.warn("Unauthorized access attempt to $path from ${request.remoteAddr}")
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key")
                return
            }
        }

        if (path == "/api/router/instance/register") {
            val instanceId = request.getParameter("instanceId") ?: ""
            val host = request.getParameter("host") ?: ""
            val portStr = request.getParameter("port") ?: ""

            if (!paramPattern.matches(instanceId) || instanceId.length > 64) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid instanceId")
                return
            }
            if (!hostPattern.matches(host) || host.length > 128) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid host")
                return
            }
            val port = portStr.toIntOrNull()
            if (port == null || port < 1 || port > 65535) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid port")
                return
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun writeError(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json"
        response.writer.write(objectMapper.writeValueAsString(ResultVo.error<String>(message)))
    }
}
