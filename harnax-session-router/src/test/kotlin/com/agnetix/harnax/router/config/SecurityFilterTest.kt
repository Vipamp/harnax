package com.agnetix.harnax.router.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import tools.jackson.databind.ObjectMapper

class SecurityFilterTest {

    private lateinit var request: HttpServletRequest
    private lateinit var response: HttpServletResponse
    private lateinit var filterChain: FilterChain
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        request = mock(HttpServletRequest::class.java)
        response = mock(HttpServletResponse::class.java)
        filterChain = mock(FilterChain::class.java)
        objectMapper = mock(ObjectMapper::class.java)
        `when`(response.writer).thenReturn(mock(java.io.PrintWriter::class.java))
    }

    private fun createFilter(apiKey: String = "secret-key", securityEnabled: Boolean = true): SecurityFilter = SecurityFilter(apiKey, securityEnabled, objectMapper)

    private fun mockApiKey(key: String?) {
        `when`(request.getHeader("X-Api-Key")).thenReturn(key)
    }

    // ==================== Health endpoint always accessible ====================

    @Test
    fun `health endpoint is always accessible without API key`() {
        val filter = createFilter()
        `when`(request.requestURI).thenReturn("/api/router/health")

        filter.doFilterInternal(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }

    @Test
    fun `health endpoint is accessible even when security enabled`() {
        val filter = createFilter(securityEnabled = true)
        `when`(request.requestURI).thenReturn("/api/router/health")

        filter.doFilterInternal(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }

    // ==================== Security disabled ====================

    @Test
    fun `allows all requests when security is disabled`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")

        filter.doFilterInternal(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }

    // ==================== API Key authentication ====================

    @Test
    fun `allows request with valid API key to protected endpoint`() {
        val filter = createFilter(apiKey = "secret-key")
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        mockApiKey("secret-key")

        filter.doFilterInternal(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }

    @Test
    fun `rejects request with invalid API key to protected endpoint`() {
        val filter = createFilter(apiKey = "secret-key")
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        mockApiKey("wrong-key")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
        verify(filterChain, never()).doFilter(request, response)
    }

    @Test
    fun `rejects request with missing API key to protected endpoint`() {
        val filter = createFilter(apiKey = "secret-key")
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        mockApiKey(null)
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
        verify(filterChain, never()).doFilter(request, response)
    }

    @Test
    fun `rejects request when API key config is blank`() {
        val filter = createFilter(apiKey = "")
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        mockApiKey("any-key")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
    }

    // ==================== All non-health endpoints require auth ====================

    @Test
    fun `protects heartbeat endpoint`() {
        val filter = createFilter(apiKey = "secret-key")
        `when`(request.requestURI).thenReturn("/api/router/instance/heartbeat")
        mockApiKey("wrong")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
    }

    @Test
    fun `protects unregister endpoint`() {
        val filter = createFilter(apiKey = "secret-key")
        `when`(request.requestURI).thenReturn("/api/router/instance/unregister")
        mockApiKey(null)
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
    }

    @Test
    fun `protects drain endpoint`() {
        val filter = createFilter(apiKey = "secret-key")
        `when`(request.requestURI).thenReturn("/api/router/instance/drain")
        mockApiKey(null)
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
    }

    @Test
    fun `proxy endpoints require API key when security enabled`() {
        val filter = createFilter(apiKey = "secret-key")
        `when`(request.requestURI).thenReturn("/api/router/agent/chat")
        mockApiKey(null)
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
    }

    @Test
    fun `proxy stream endpoint requires API key when security enabled`() {
        val filter = createFilter(apiKey = "secret-key")
        `when`(request.requestURI).thenReturn("/api/router/agent/chat/stream")
        mockApiKey(null)
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_UNAUTHORIZED
    }

    @Test
    fun `proxy endpoint passes with valid API key`() {
        val filter = createFilter(apiKey = "secret-key")
        `when`(request.requestURI).thenReturn("/api/router/agent/chat")
        mockApiKey("secret-key")

        filter.doFilterInternal(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }

    // ==================== Parameter validation on register ====================

    @Test
    fun `register passes with valid parameters`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1")
        `when`(request.getParameter("host")).thenReturn("10.0.0.1")
        `when`(request.getParameter("port")).thenReturn("8082")

        filter.doFilterInternal(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }

    @Test
    fun `register rejects invalid instanceId with special characters`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1<script>")
        `when`(request.getParameter("host")).thenReturn("10.0.0.1")
        `when`(request.getParameter("port")).thenReturn("8082")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
        verify(filterChain, never()).doFilter(request, response)
    }

    @Test
    fun `register rejects instanceId exceeding max length`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("a".repeat(65))
        `when`(request.getParameter("host")).thenReturn("10.0.0.1")
        `when`(request.getParameter("port")).thenReturn("8082")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
    }

    @Test
    fun `register rejects invalid host`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1")
        `when`(request.getParameter("host")).thenReturn("host with spaces")
        `when`(request.getParameter("port")).thenReturn("8082")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
    }

    @Test
    fun `register rejects port below range`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1")
        `when`(request.getParameter("host")).thenReturn("10.0.0.1")
        `when`(request.getParameter("port")).thenReturn("0")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
    }

    @Test
    fun `register rejects port above range`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1")
        `when`(request.getParameter("host")).thenReturn("10.0.0.1")
        `when`(request.getParameter("port")).thenReturn("65536")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
    }

    @Test
    fun `register rejects non-numeric port`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1")
        `when`(request.getParameter("host")).thenReturn("10.0.0.1")
        `when`(request.getParameter("port")).thenReturn("abc")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
    }

    // ==================== SSRF protection ====================

    @Test
    fun `register rejects loopback address 127`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1")
        `when`(request.getParameter("host")).thenReturn("127.0.0.1")
        `when`(request.getParameter("port")).thenReturn("8082")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
    }

    @Test
    fun `register rejects localhost hostname`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1")
        `when`(request.getParameter("host")).thenReturn("localhost")
        `when`(request.getParameter("port")).thenReturn("8082")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
    }

    @Test
    fun `register rejects cloud metadata endpoint`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1")
        `when`(request.getParameter("host")).thenReturn("metadata.google.internal")
        `when`(request.getParameter("port")).thenReturn("80")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
    }

    @Test
    fun `register rejects link-local address`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1")
        `when`(request.getParameter("host")).thenReturn("169.254.169.254")
        `when`(request.getParameter("port")).thenReturn("80")
        `when`(objectMapper.writeValueAsString(any())).thenReturn("{}")

        filter.doFilterInternal(request, response, filterChain)

        verify(response).status = HttpServletResponse.SC_BAD_REQUEST
    }

    @Test
    fun `register accepts valid internal addresses`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1.prod.dc1")
        `when`(request.getParameter("host")).thenReturn("10.0.0.1")
        `when`(request.getParameter("port")).thenReturn("8082")

        filter.doFilterInternal(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }

    @Test
    fun `register accepts boundary port values`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1")
        `when`(request.getParameter("host")).thenReturn("10.0.0.1")
        `when`(request.getParameter("port")).thenReturn("1")

        filter.doFilterInternal(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }

    @Test
    fun `register accepts max valid port`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/register")
        `when`(request.getParameter("instanceId")).thenReturn("inst-1")
        `when`(request.getParameter("host")).thenReturn("10.0.0.1")
        `when`(request.getParameter("port")).thenReturn("65535")

        filter.doFilterInternal(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }

    @Test
    fun `parameter validation only applies to register endpoint`() {
        val filter = createFilter(securityEnabled = false)
        `when`(request.requestURI).thenReturn("/api/router/instance/heartbeat")

        filter.doFilterInternal(request, response, filterChain)

        verify(filterChain).doFilter(request, response)
    }
}
