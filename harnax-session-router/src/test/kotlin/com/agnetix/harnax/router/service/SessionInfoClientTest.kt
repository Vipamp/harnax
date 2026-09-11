package com.agnetix.harnax.router.service

import com.agnetix.harnax.common.dto.ResultVo
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class SessionInfoClientTest {

    /**
     * 启动一个本地 HttpServer，注入 SessionInfoClient (通过 AdminClientService)，调用一次 getSessionInfo
     * 返回：调用计数 + result
     */
    private fun withHttpServer(
        statusCode: Int = 200,
        body: String = "",
        block: (String, SessionInfoClient, AtomicInteger) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val counter = AtomicInteger(0)
        server.createContext("/api/admin/internal/sessions/") { exchange ->
            counter.incrementAndGet()
            val respBody = body.toByteArray()
            exchange.responseHeaders.set(HttpHeaders.CONTENT_TYPE, "application/json")
            exchange.sendResponseHeaders(statusCode, respBody.size.toLong())
            exchange.responseBody.use { it.write(respBody) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}"
            val adminClientService = AdminClientService(url, "test-secret", 2000L, 3000L)
            val client = SessionInfoClient(adminClientService, 3000L)
            block(url, client, counter)
        } finally {
            server.stop(0)
        }
    }

    private val mapper = ObjectMapper()

    @Test
    fun `getSessionInfo returns data on valid response`() {
        val sessionInfo = AdminClientService.SessionInfo(
            sessionId = "sess-1",
            agentId = 10L,
            agentName = "my-agent",
            modelId = 5L,
            modelName = "gpt-4",
            tenantId = 1L,
        )
        val body = mapper.writeValueAsString(ResultVo.success(sessionInfo))
        withHttpServer(body = body) { _, client, _ ->
            val result = client.getSessionInfo("sess-1")
            assertNotNull(result)
            assertEquals("sess-1", result!!.sessionId)
            assertEquals(10L, result.agentId)
            assertEquals("my-agent", result.agentName)
            assertEquals(5L, result.modelId)
            assertEquals("gpt-4", result.modelName)
            assertEquals(1L, result.tenantId)
        }
    }

    @Test
    fun `getSessionInfo returns null when admin returns null data`() {
        val body = mapper.writeValueAsString(ResultVo.success<AdminClientService.SessionInfo?>(null))
        withHttpServer(body = body) { _, client, _ ->
            val result = client.getSessionInfo("unknown-session")
            assertNull(result)
        }
    }

    @Test
    fun `getSessionInfo returns null on HTTP error`() {
        withHttpServer(statusCode = 500) { _, client, _ ->
            val result = client.getSessionInfo("error-session")
            assertNull(result)
        }
    }

    @Test
    fun `cache returns same result without second HTTP call`() {
        val sessionInfo = AdminClientService.SessionInfo(sessionId = "sess-cached", agentId = 1L, agentName = "agent")
        val body = mapper.writeValueAsString(ResultVo.success(sessionInfo))
        withHttpServer(body = body) { _, client, counter ->
            val result1 = client.getSessionInfo("sess-cached")
            val result2 = client.getSessionInfo("sess-cached")
            assertNotNull(result1)
            assertEquals(result1!!.sessionId, result2!!.sessionId)
            assertEquals(1, counter.get(), "Second call should be served from cache")
        }
    }

    @Test
    fun `different session IDs trigger separate HTTP calls`() {
        val info1 = AdminClientService.SessionInfo(sessionId = "s1", agentId = 1L)
        val info2 = AdminClientService.SessionInfo(sessionId = "s2", agentId = 2L)
        // 因为两个 session 都命中同一个 handler，无法区分 response，
        // 这里只验证不同 sessionId 会发起两次 HTTP 请求
        val body = mapper.writeValueAsString(ResultVo.success(info1))
        withHttpServer(body = body) { _, client, counter ->
            val r1 = client.getSessionInfo("s1")
            val r2 = client.getSessionInfo("s2")
            assertEquals(1L, r1!!.agentId)
            assertEquals(1L, r2!!.agentId)
            assertEquals(2, counter.get())
            // 避免 unused warning
            @Suppress("UNUSED_VARIABLE")
            val unused = info2
        }
    }

    @Test
    fun `session info with null optional fields`() {
        val sessionInfo = AdminClientService.SessionInfo(
            sessionId = "sess-minimal",
            agentId = null,
            agentName = null,
            modelId = null,
            modelName = null,
            tenantId = null,
        )
        val body = mapper.writeValueAsString(ResultVo.success(sessionInfo))
        withHttpServer(body = body) { _, client, _ ->
            val result = client.getSessionInfo("sess-minimal")!!
            assertEquals("sess-minimal", result.sessionId)
            assertNull(result.agentId)
            assertNull(result.agentName)
            assertNull(result.modelId)
            assertNull(result.modelName)
            assertNull(result.tenantId)
        }
    }

    @Test
    fun `connection failure returns null without throwing`() {
        // 启动一个端口但立刻关闭，触发连接拒绝
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.stop(0)
        val adminClientService = AdminClientService("http://127.0.0.1:$port", "secret", 500L, 1000L)
        val client = SessionInfoClient(adminClientService, 1000L)
        val result = client.getSessionInfo("any")
        assertNull(result)
    }

    @Test
    fun `slow response times out and returns null`() {
        // 启动一个永远不响应的 server，验证超时降级
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/admin/internal/sessions/") { exchange ->
            // 不 sendResponseHeaders，连接一直挂着
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
            }
            exchange.sendResponseHeaders(200, -1)
        }
        server.start()
        try {
            val port = server.address.port
            val adminClientService = AdminClientService("http://127.0.0.1:$port", "secret", 500L, 800L)
            val client = SessionInfoClient(adminClientService, 800L)
            val start = System.currentTimeMillis()
            val result = client.getSessionInfo("slow")
            val elapsed = System.currentTimeMillis() - start
            assertNull(result)
            // 超时上限 = responseTimeout + 1000ms = 1800ms
            assertTrue(elapsed < 2500, "Should timeout within bounded time, elapsed=$elapsed")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `an answer of no such session is cached`() {
        val body = mapper.writeValueAsString(ResultVo.success<AdminClientService.SessionInfo?>(null))
        withHttpServer(body = body) { _, client, counter ->
            assertNull(client.getSessionInfo("gone"))
            assertNull(client.getSessionInfo("gone"))
            assertEquals(1, counter.get(), "admin answered once; asking again adds nothing")
        }
    }

    @Test
    fun `admin not answering is not cached`() {
        withHttpServer(statusCode = 500) { _, client, counter ->
            // A failed lookup is what the guard fails open on. Caching it would leave every session
            // this router knows looking like an unknown one for the rest of the cache's life.
            assertNull(client.getSessionInfo("blip"))
            assertNull(client.getSessionInfo("blip"))
            assertEquals(2, counter.get(), "an unreachable admin must not be remembered as an answer")
        }
    }
}
