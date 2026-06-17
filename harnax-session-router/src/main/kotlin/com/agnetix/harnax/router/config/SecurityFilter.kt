package com.agnetix.harnax.router.config

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.entity.AgentInstance
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
import java.security.MessageDigest

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class SecurityFilter(
    @Value($$"${router.security.api-key:}")
    private val apiKey: String,
    @Value($$"${router.security.enabled:true}")
    private val securityEnabled: Boolean,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(SecurityFilter::class.java)

    init {
        if (securityEnabled) {
            validateApiKey(apiKey)
        }
    }

    private fun validateApiKey(key: String) {
        if (key.isBlank()) {
            log.error("SECURITY WARNING: API key is empty but security is enabled!")
            throw IllegalStateException("API key must be configured when security is enabled")
        }
        if (key == "change-me-in-production" || key.length < 16) {
            log.warn("SECURITY WARNING: Weak API key detected! Please set a strong key (min 16 chars)")
        }
        log.info("Security filter initialized with API key authentication enabled")
    }

    private val unprotectedEndpoints = setOf(
        "/api/router/health",
    )

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

        if (path in unprotectedEndpoints) {
            filterChain.doFilter(request, response)
            return
        }

        if (securityEnabled) {
            val providedKey = request.getHeader("X-Api-Key") ?: ""
            if (apiKey.isBlank() || !constantTimeEquals(providedKey, apiKey)) {
                log.warn("Unauthorized access attempt to $path from ${request.remoteAddr}")
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key")
                return
            }
        }

        if (path == "/api/router/instance/register") {
            val instanceId = request.getParameter("instanceId") ?: ""
            val host = request.getParameter("host") ?: ""
            val portStr = request.getParameter("port") ?: ""

            // Validate instanceId format and length
            if (instanceId.isBlank() || !paramPattern.matches(instanceId) || instanceId.length > 64) {
                log.warn("Registration rejected: invalid instanceId format")
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid instanceId format")
                return
            }

            // Validate host format and length
            if (host.isBlank() || !hostPattern.matches(host) || host.length > 128) {
                log.warn("Registration rejected: invalid host format for instance $instanceId")
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid host format")
                return
            }

            // Block dangerous addresses (loopback, link-local, metadata endpoints)
            if (AgentInstance.isBlockedHost(host)) {
                log.warn("Registration rejected: blocked host $host for instance $instanceId")
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Host address not allowed")
                return
            }

            // Validate port range
            val port = portStr.toIntOrNull()
            if (port == null || port < 1 || port > 65535) {
                log.warn("Registration rejected: invalid port $portStr for instance $instanceId")
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid port number")
                return
            }

            // Block privileged ports
            if (port < 1024) {
                log.warn("Registration rejected: privileged port $port for instance $instanceId")
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "Privileged ports not allowed")
                return
            }

            log.info("Registration validated for instance $instanceId at $host:$port")
        }

        filterChain.doFilter(request, response)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    private fun writeError(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json"
        response.writer.write(objectMapper.writeValueAsString(ResultVo.error<String>(message)))
    }
}
