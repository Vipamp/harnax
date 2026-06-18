package com.agnetix.harnax.router.config

import com.agnetix.harnax.auth.AuthContext
import com.agnetix.harnax.auth.AuthContextHolder
import com.agnetix.harnax.auth.CallerType
import com.agnetix.harnax.router.entity.ApiCallLog
import com.agnetix.harnax.router.service.ApiCallLogService
import com.agnetix.harnax.router.service.SessionInfoClient
import com.agnetix.harnax.router.service.SessionInfo
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

class ApiCallLogFilterTest {

    private lateinit var apiCallLogService: ApiCallLogService
    private lateinit var sessionInfoClient: SessionInfoClient
    private lateinit var filter: ApiCallLogFilter
    private lateinit var request: HttpServletRequest
    private lateinit var response: HttpServletResponse
    private lateinit var chain: FilterChain

    private val noopOutputStream = object : ServletOutputStream() {
        override fun isReady() = true
        override fun setWriteListener(listener: WriteListener?) {}
        override fun write(b: Int) {}
        override fun write(b: ByteArray) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    @BeforeEach
    fun setUp() {
        apiCallLogService = mock(ApiCallLogService::class.java)
        sessionInfoClient = mock(SessionInfoClient::class.java)
        filter = ApiCallLogFilter(apiCallLogService, sessionInfoClient)
        request = mock(HttpServletRequest::class.java)
        response = mock(HttpServletResponse::class.java)
        chain = mock(FilterChain::class.java)
        `when`(response.outputStream).thenReturn(noopOutputStream)
    }

    @AfterEach
    fun tearDown() {
        AuthContextHolder.clear()
    }

    @Test
    fun `health path is excluded`() {
        `when`(request.requestURI).thenReturn("/health")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
        verify(apiCallLogService, never()).record(any())
    }

    @Test
    fun `actuator path is excluded`() {
        `when`(request.requestURI).thenReturn("/actuator/prometheus")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
        verify(apiCallLogService, never()).record(any())
    }

    @Test
    fun `ai path is excluded`() {
        `when`(request.requestURI).thenReturn("/ai/test")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
        verify(apiCallLogService, never()).record(any())
    }

    @Test
    fun `router health path is excluded`() {
        `when`(request.requestURI).thenReturn("/api/router/health")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
        verify(apiCallLogService, never()).record(any())
    }

    @Test
    fun `router metrics cache path is excluded`() {
        `when`(request.requestURI).thenReturn("/api/router/metrics/cache")
        filter.doFilterInternal(request, response, chain)
        verify(chain).doFilter(request, response)
        verify(apiCallLogService, never()).record(any())
    }

    @Test
    fun `agent chat path is not excluded and records log`() {
        AuthContextHolder.set(AuthContext("user-1", "router:invoke"))
        `when`(request.requestURI).thenReturn("/api/router/agent/chat")
        `when`(request.method).thenReturn("POST")
        `when`(apiCallLogService.buildLogEntry(
            anyString(), anyString(), any(), any(), any(), any(), any(), any(),
            anyString(), anyString(), any(), anyInt(), anyBoolean(), any(),
            any(), any(), any(), any(),
        )).thenReturn(ApiCallLog())

        `when`(response.status).thenReturn(200)

        filter.doFilterInternal(request, response, chain)

        verify(chain).doFilter(any(), any())
        verify(apiCallLogService).record(any())
    }

    @Test
    fun `log records caller info from AuthContext`() {
        val context = AuthContext(
            callerId = "api-key-1",
            scopes = setOf("router:invoke"),
            callerType = CallerType.EXTERNAL_API,
            tenantId = 42L,
        )
        AuthContextHolder.set(context)
        `when`(request.requestURI).thenReturn("/api/router/agent/command")
        `when`(request.method).thenReturn("POST")
        `when`(response.status).thenReturn(200)

        val logCaptor = ArgumentCaptor.forClass(ApiCallLog::class.java)
        val entry = ApiCallLog().apply { callerId = "api-key-1" }
        `when`(apiCallLogService.buildLogEntry(
            anyString(), anyString(), any(), any(), any(), any(), any(), any(),
            anyString(), anyString(), any(), anyInt(), anyBoolean(), any(),
            any(), any(), any(), any(),
        )).thenReturn(entry)

        filter.doFilterInternal(request, response, chain)

        verify(apiCallLogService).buildLogEntry(
            eq("api-key-1"),
            eq("EXTERNAL_API"),
            eq(42L),
            any(), any(), any(), any(), any(),
            anyString(), anyString(), any(),
            eq(200), eq(true), any(),
            any(), any(), any(), any(),
        )
    }

    @Test
    fun `session info is fetched when sessionId is extracted`() {
        AuthContextHolder.set(AuthContext("user", "router:invoke"))
        `when`(request.requestURI).thenReturn("/api/router/agent/chat/sess-123")
        `when`(request.method).thenReturn("POST")
        `when`(response.status).thenReturn(200)

        val sessionInfo = SessionInfo(
            sessionId = "sess-123",
            agentId = 10L,
            agentName = "my-agent",
            modelId = 5L,
            modelName = "gpt-4",
            tenantId = 1L,
        )
        `when`(sessionInfoClient.getSessionInfo("sess-123")).thenReturn(sessionInfo)
        `when`(apiCallLogService.buildLogEntry(
            anyString(), anyString(), any(), any(), any(), any(), any(), any(),
            anyString(), anyString(), any(), anyInt(), anyBoolean(), any(),
            any(), any(), any(), any(),
        )).thenReturn(ApiCallLog())

        filter.doFilterInternal(request, response, chain)

        verify(sessionInfoClient).getSessionInfo("sess-123")
        verify(apiCallLogService).buildLogEntry(
            anyString(), anyString(), any(),
            eq("sess-123"),
            eq(10L), eq("my-agent"), eq(5L), eq("gpt-4"),
            anyString(), anyString(), any(), anyInt(), anyBoolean(), any(),
            any(), any(), any(), any(),
        )
    }

    @Test
    fun `500 status code marks success as false`() {
        AuthContextHolder.set(AuthContext("user", "router:invoke"))
        `when`(request.requestURI).thenReturn("/api/router/agent/chat")
        `when`(request.method).thenReturn("POST")
        `when`(response.status).thenReturn(500)
        `when`(apiCallLogService.buildLogEntry(
            anyString(), anyString(), any(), any(), any(), any(), any(), any(),
            anyString(), anyString(), any(), anyInt(), anyBoolean(), any(),
            any(), any(), any(), any(),
        )).thenReturn(ApiCallLog())

        filter.doFilterInternal(request, response, chain)

        verify(apiCallLogService).buildLogEntry(
            anyString(), anyString(), any(), any(), any(), any(), any(), any(),
            anyString(), anyString(), any(),
            eq(500), eq(false), any(),
            any(), any(), any(), any(),
        )
    }

    @Test
    fun `filter does not throw when sessionInfoClient fails`() {
        AuthContextHolder.set(AuthContext("user", "router:invoke"))
        `when`(request.requestURI).thenReturn("/api/router/agent/chat/sess-err")
        `when`(request.method).thenReturn("POST")
        `when`(response.status).thenReturn(200)
        `when`(sessionInfoClient.getSessionInfo(anyString())).thenThrow(RuntimeException("Admin down"))
        `when`(apiCallLogService.buildLogEntry(
            anyString(), anyString(), any(), any(), any(), any(), any(), any(),
            anyString(), anyString(), any(), anyInt(), anyBoolean(), any(),
            any(), any(), any(), any(),
        )).thenReturn(ApiCallLog())

        assertDoesNotThrow {
            filter.doFilterInternal(request, response, chain)
        }
        verify(chain).doFilter(any(), any())
    }

    @Test
    fun `unknown caller when no AuthContext`() {
        `when`(request.requestURI).thenReturn("/api/router/agent/chat")
        `when`(request.method).thenReturn("POST")
        `when`(response.status).thenReturn(200)
        `when`(apiCallLogService.buildLogEntry(
            anyString(), anyString(), any(), any(), any(), any(), any(), any(),
            anyString(), anyString(), any(), anyInt(), anyBoolean(), any(),
            any(), any(), any(), any(),
        )).thenReturn(ApiCallLog())

        filter.doFilterInternal(request, response, chain)

        verify(apiCallLogService).buildLogEntry(
            eq("unknown"),
            eq("UNKNOWN"),
            any(), any(), any(), any(), any(), any(),
            anyString(), anyString(), any(), anyInt(), anyBoolean(), any(),
            any(), any(), any(), any(),
        )
    }

    @Test
    fun `X-Request-Id header is used as requestId fallback`() {
        AuthContextHolder.set(AuthContext("user", "router:invoke"))
        `when`(request.requestURI).thenReturn("/api/router/agent/chat")
        `when`(request.method).thenReturn("POST")
        `when`(request.getHeader("X-Request-Id")).thenReturn("header-req-id")
        `when`(response.status).thenReturn(200)
        `when`(apiCallLogService.buildLogEntry(
            anyString(), anyString(), any(), any(), any(), any(), any(), any(),
            anyString(), anyString(), any(), anyInt(), anyBoolean(), any(),
            any(), any(), any(), any(),
        )).thenReturn(ApiCallLog())

        filter.doFilterInternal(request, response, chain)

        verify(apiCallLogService).buildLogEntry(
            anyString(), anyString(), any(), any(), any(), any(), any(), any(),
            anyString(), anyString(), any(), anyInt(), anyBoolean(), any(),
            any(), eq("header-req-id"),
        )
    }
}
