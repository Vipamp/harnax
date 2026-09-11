package com.agnetix.harnax.router.config

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.entity.AgentInstance
import com.agnetix.harnax.router.support.IdFormat
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/**
 * Rejects malformed instance registrations before they reach [com.agnetix.harnax.router.controller.InstanceRegistryController].
 *
 * Reads the same request parameters the controller binds with `@RequestParam`, so both see query
 * string or form fields and neither can be passed by a registration that carries them some other
 * way. The controller repeats the host and port checks it cares about most — this filter is a cheap
 * front line, not the authority.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class InstanceRegistrationValidationFilter(
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(InstanceRegistrationValidationFilter::class.java)

    private val hostPattern = Regex("^[a-zA-Z0-9._-]+$")

    public override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // requestURI keeps matrix parameters and Spring drops them before matching the route, so
        // `/register;junk=1` used to reach the controller having skipped everything below.
        val path = request.requestURI.substringBefore(';')

        if (path == "/api/router/instance/register") {
            val instanceId = request.getParameter("instanceId") ?: ""
            val host = request.getParameter("host") ?: ""
            val portStr = request.getParameter("port") ?: ""

            if (!IdFormat.isInstanceId(instanceId)) {
                log.warn("Registration rejected: invalid instanceId format")
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid instanceId format")
                return
            }

            if (host.isBlank() || !hostPattern.matches(host) || host.length > 128) {
                log.warn("Registration rejected: invalid host format for instance $instanceId")
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid host format")
                return
            }

            if (AgentInstance.isBlockedHost(host)) {
                log.warn("Registration rejected: blocked host $host for instance $instanceId")
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Host address not allowed")
                return
            }

            val port = portStr.toIntOrNull()
            if (port == null || port < 1 || port > 65535) {
                log.warn("Registration rejected: invalid port $portStr for instance $instanceId")
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid port number")
                return
            }

            // Use the port range defined on AgentInstance (8000-9999) to stay aligned with entity validation.
            if (!AgentInstance.isValidPort(port)) {
                val reason = when {
                    port < 1024 -> "Privileged ports not allowed"
                    else -> "Port must be in range ${AgentInstance.MIN_PORT}-${AgentInstance.MAX_PORT}"
                }
                val status = if (port < 1024) HttpServletResponse.SC_FORBIDDEN else HttpServletResponse.SC_BAD_REQUEST
                log.warn("Registration rejected: port $port for instance $instanceId ($reason)")
                writeError(response, status, reason)
                return
            }

            log.info("Registration validated for instance $instanceId at $host:$port")
        }

        filterChain.doFilter(request, response)
    }

    private fun writeError(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json"
        response.writer.write(objectMapper.writeValueAsString(ResultVo.error<String>(message)))
    }
}
