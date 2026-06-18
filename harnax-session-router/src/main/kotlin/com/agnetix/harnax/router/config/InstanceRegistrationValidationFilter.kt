package com.agnetix.harnax.router.config

import com.agnetix.harnax.common.dto.ResultVo
import com.agnetix.harnax.router.entity.AgentInstance
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class InstanceRegistrationValidationFilter(
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(InstanceRegistrationValidationFilter::class.java)

    private val paramPattern = Regex("^[a-zA-Z0-9._-]+$")
    private val hostPattern = Regex("^[a-zA-Z0-9._-]+$")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI

        if (path == "/api/router/instance/register") {
            val instanceId = request.getParameter("instanceId") ?: ""
            val host = request.getParameter("host") ?: ""
            val portStr = request.getParameter("port") ?: ""

            if (instanceId.isBlank() || !paramPattern.matches(instanceId) || instanceId.length > 64) {
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

            if (port < 1024) {
                log.warn("Registration rejected: privileged port $port for instance $instanceId")
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "Privileged ports not allowed")
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
