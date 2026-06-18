package com.agnetix.harnax.router.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import tools.jackson.databind.ObjectMapper

class InstanceRegistrationValidationFilterTest {

    private lateinit var filter: InstanceRegistrationValidationFilter
    private lateinit var request: HttpServletRequest
    private lateinit var response: HttpServletResponse
    private lateinit var chain: FilterChain

    @BeforeEach
    fun setUp() {
        filter = InstanceRegistrationValidationFilter(ObjectMapper())
        request = mock(HttpServletRequest::class.java)
        response = mock(HttpServletResponse::class.java)
        chain = mock(FilterChain::class.java)

        `when`(response.writer).thenReturn(java.io.PrintWriter(java.io.StringWriter()))
    }

    private fun registerRequest(instanceId: String, host: String, port: String) {
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn(instanceId)
        `when`(request.getParameter("host")).thenReturn(host)
        `when`(request.getParameter("port")).thenReturn(port)
    }

    @Test
    fun `valid registration passes through`() {
        registerRequest("inst-001", "10.0.0.1", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
        verify(response, never()).setStatus(anyInt())
    }

    @Test
    fun `blank instanceId is rejected`() {
        registerRequest("", "10.0.0.1", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `instanceId with special characters is rejected`() {
        registerRequest("inst@#$%", "10.0.0.1", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `instanceId exceeding 64 chars is rejected`() {
        registerRequest("a".repeat(65), "10.0.0.1", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `instanceId exactly 64 chars is accepted`() {
        registerRequest("a".repeat(64), "10.0.0.1", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `blank host is rejected`() {
        registerRequest("inst-1", "", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `host with special characters is rejected`() {
        registerRequest("inst-1", "host name;drop table", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `host exceeding 128 chars is rejected`() {
        registerRequest("inst-1", "a".repeat(129), "8082")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `loopback 127_0_0_1 is blocked`() {
        // 127.0.0.1 has dots so it passes hostPattern, but isBlockedHost catches it
        registerRequest("inst-1", "127.0.0.1", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `localhost hostname is blocked`() {
        registerRequest("inst-1", "localhost", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `link-local 169_254 is blocked`() {
        registerRequest("inst-1", "169.254.169.254", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `private IP 10_x is allowed`() {
        registerRequest("inst-1", "10.0.1.50", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `private IP 192_168 is allowed`() {
        registerRequest("inst-1", "192.168.1.100", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `private IP 172_16 is allowed`() {
        registerRequest("inst-1", "172.16.0.1", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `non-numeric port is rejected`() {
        registerRequest("inst-1", "10.0.0.1", "abc")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `port 0 is rejected`() {
        registerRequest("inst-1", "10.0.0.1", "0")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `port 65536 is rejected`() {
        registerRequest("inst-1", "10.0.0.1", "65536")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `privileged port 80 is forbidden`() {
        registerRequest("inst-1", "10.0.0.1", "80")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_FORBIDDEN
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `privileged port 1023 is forbidden`() {
        registerRequest("inst-1", "10.0.0.1", "1023")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_FORBIDDEN
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `port 1024 is allowed`() {
        registerRequest("inst-1", "10.0.0.1", "1024")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `port 8082 is allowed`() {
        registerRequest("inst-1", "10.0.0.1", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `non-register path passes through without validation`() {
        `when`(request.requestURI).thenReturn("/api/router/agent/chat")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
        verify(request, never()).getParameter(anyString())
    }

    @Test
    fun `instanceId with dots and hyphens is allowed`() {
        registerRequest("agent-service.prod-01", "10.0.0.1", "8082")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `empty port string is rejected`() {
        registerRequest("inst-1", "10.0.0.1", "")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }

    @Test
    fun `negative port is rejected`() {
        registerRequest("inst-1", "10.0.0.1", "-1")
        filter.doFilterInternal(request, response, chain)
        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(chain, never()).doFilter(request, response)
    }
}
