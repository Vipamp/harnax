package com.agnetix.harnax.admin.util

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * RemoteJsonFetcher 单元测试
 *
 * 用 JDK 自带的 HttpServer 起一个本机端口随机的真服务，验证的是那几个不能靠调用方自觉的护栏：
 * 只走 http(s)、不跟跳、限响应体大小、把 WWW-Authenticate 原样带回去。GET 与表单 POST 共用一条
 * 收发链路，所以两条都得试；失败响应的错误体也要能读到，因为「换票被拒」的理由只写在 400 的体里。
 *
 * @author agnetix
 * @since 2026-09-09
 */
@DisplayName("RemoteJsonFetcher 出站请求护栏")
class RemoteJsonFetcherTest {

    private val fetcher = RemoteJsonFetcher(jacksonObjectMapper())

    private lateinit var server: HttpServer

    private var base: String = ""

    private val jsonHits = AtomicInteger()

    private var postedForm: String? = null

    @BeforeEach
    fun startServer() {
        jsonHits.set(0)
        postedForm = null
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/json") { exchange ->
            jsonHits.incrementAndGet()
            reply(exchange, 200, """{"issuer":"https://as.example.com","token_endpoint":"https://as.example.com/token"}""", null)
        }
        server.createContext("/html") { exchange ->
            reply(exchange, 200, "<html><body>Sign in</body></html>", null)
        }
        server.createContext("/missing") { exchange ->
            reply(exchange, 404, """{"error":"not_found"}""", null)
        }
        server.createContext("/token-refused") { exchange ->
            postedForm = exchange.requestBody.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
            reply(exchange, 400, """{"error":"invalid_grant","error_description":"code_verifier did not match"}""", null)
        }
        server.createContext("/refused-silent") { exchange ->
            reply(exchange, 400, "", null)
        }
        server.createContext("/big") { exchange ->
            reply(exchange, 200, "x".repeat(70 * 1024), null)
        }
        server.createContext("/challenge") { exchange ->
            reply(exchange, 401, "", """Bearer error="invalid_token", resource_metadata="https://as.example.com/rm"""")
        }
        server.createContext("/challenge-split") { exchange ->
            reply(exchange, 401, "", "Bearer scope=\"mcp:read\"", "Bearer resource_metadata=\"https://as.example.com/rm\"")
        }
        server.createContext("/array") { exchange ->
            reply(exchange, 200, """[{"token_endpoint":"https://as.example.com/token"}]""", null)
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "$base/json")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun reply(
        exchange: com.sun.net.httpserver.HttpExchange,
        status: Int,
        body: String,
        vararg wwwAuthenticate: String?,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        wwwAuthenticate.filterNotNull().forEach { exchange.responseHeaders.add("WWW-Authenticate", it) }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Test
    @DisplayName("200 + JSON - 解析成节点并带上状态")
    fun `fetch should parse a json body and keep the status`() {
        val result = fetcher.fetch("$base/json")

        assertTrue(result.ok)
        assertEquals(200, result.status)
        assertEquals("https://as.example.com/token", result.field("token_endpoint"))
        assertNull(result.field("authorization_endpoint"))
        assertTrue(result.array("scopes_supported").isEmpty())
    }

    @Test
    @DisplayName("200 + HTML - 不是元数据，交回空节点而不是抛错")
    fun `fetch should return no json for a body that is not an object`() {
        val result = fetcher.fetch("$base/html")

        assertTrue(result.ok)
        assertNull(result.json)
    }

    @Test
    @DisplayName("404 带错误体 - 状态判失败，但理由能读到")
    fun `fetch should report a non-2xx answer without throwing`() {
        val result = fetcher.fetch("$base/missing")

        assertFalse(result.ok)
        assertEquals(404, result.status)
        assertEquals("not_found", result.field("error"))
    }

    @Test
    @DisplayName("表单 POST - 400 的拒绝理由可读，值都按表单编码")
    fun `postForm should surface the refusal reason of an error response`() {
        val result = fetcher.postForm(
            "$base/token-refused",
            mapOf("grant_type" to "authorization_code", "code" to "a+b/c", "client_id" to "harnax"),
        )

        assertFalse(result.ok)
        assertEquals(400, result.status)
        assertEquals("invalid_grant", result.field("error"))
        assertEquals("code_verifier did not match", result.field("error_description"))
        assertEquals("grant_type=authorization_code&code=a%2Bb%2Fc&client_id=harnax", postedForm)
    }

    @Test
    @DisplayName("400 但没有任何体 - 判失败，不编出理由来")
    fun `fetch should return no json for an error answer without a body`() {
        val result = fetcher.fetch("$base/refused-silent")

        assertFalse(result.ok)
        assertEquals(400, result.status)
        assertNull(result.json)
    }

    @Test
    @DisplayName("401 挑战 - WWW-Authenticate 原样带回")
    fun `fetch should surface the authenticate header`() {
        val result = fetcher.fetch("$base/challenge")

        assertEquals(401, result.status)
        assertTrue(result.wwwAuthenticate!!.contains("resource_metadata="))
    }

    @Test
    @DisplayName("302 - 不跟随重定向，被指向的地址一次都没被请求")
    fun `fetch should not follow a redirect`() {
        val result = fetcher.fetch("$base/redirect")

        assertEquals(302, result.status)
        assertEquals(0, jsonHits.get())
    }

    @Test
    @DisplayName("超大响应 - 直接判失败，不把上游当下载源")
    fun `fetch should refuse an oversized body`() {
        val error = assertThrows<RemoteFetchException> { fetcher.fetch("$base/big") }

        assertTrue(error.message!!.contains("exceeds"), error.message)
    }

    @Test
    @DisplayName("非 http(s) 协议 - 建连接之前就拒掉")
    fun `fetch should refuse a non http scheme`() {
        val error = assertThrows<RemoteFetchException> { fetcher.fetch("file:///etc/passwd") }

        assertTrue(error.message!!.contains("Only http(s)"), error.message)
    }

    @Test
    @DisplayName("有协议没 host - 拒掉（http:// 少打斜杠就是这个形状）")
    fun `fetch should refuse a url without a host`() {
        val error = assertThrows<RemoteFetchException> { fetcher.fetch("http:/x") }

        assertTrue(error.message!!.contains("no host"), error.message)
    }

    @Test
    @DisplayName("连不上的地址 - 包装成可读失败")
    fun `fetch should wrap an unreachable host`() {
        val error = assertThrows<RemoteFetchException> { fetcher.fetch("http://127.0.0.1:1/.well-known/oauth-authorization-server") }

        assertTrue(error.message!!.startsWith("Request to "), error.message)
    }

    @Test
    @DisplayName("JSON 数组 - 和 HTML 一样算「这里没东西」")
    fun `fetch should return no json for an array body`() {
        val result = fetcher.fetch("$base/array")

        assertTrue(result.ok)
        assertNull(result.json)
    }

    @Test
    @DisplayName("拆成两个头的挑战 - 都带回来，指针在哪个头里都能读到")
    fun `fetch should join several authenticate headers`() {
        val result = fetcher.fetch("$base/challenge-split")

        assertTrue(result.wwwAuthenticate!!.contains("mcp:read"), result.wwwAuthenticate)
        assertTrue(result.wwwAuthenticate!!.contains("resource_metadata="), result.wwwAuthenticate)
    }

    @Test
    @DisplayName("链路本地元数据地址 - 建连接之前就拒掉")
    fun `fetch should refuse the link local metadata address`() {
        val error = assertThrows<RemoteFetchException> { fetcher.fetch("http://169.254.169.254/latest/meta-data/iam/security-credentials") }

        assertTrue(error.message!!.contains("Refusing to request"), error.message)
    }

    @Test
    @DisplayName("元数据服务主机名 - 不解析也拒掉")
    fun `fetch should refuse a metadata service hostname`() {
        val error = assertThrows<RemoteFetchException> { fetcher.fetch("http://metadata.google.internal/computeMetadata/v1/") }

        assertTrue(error.message!!.contains("metadata service host"), error.message)
    }

    @Test
    @DisplayName("地址里带凭据 - 失败信息里抹掉，不回显口令")
    fun `fetch should redact userinfo in the failure`() {
        val error = assertThrows<RemoteFetchException> { fetcher.fetch("http://admin:s3cret@127.0.0.1:1/x") }

        assertFalse(error.message!!.contains("s3cret"), error.message)
        assertTrue(error.message!!.contains("http://***@127.0.0.1:1/x"), error.message)
    }
}
