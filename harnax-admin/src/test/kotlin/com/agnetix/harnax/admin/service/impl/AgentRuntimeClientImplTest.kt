package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.auth.InternalTokenProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import java.util.concurrent.TimeUnit

/**
 * AgentRuntimeClientImpl 单元测试
 *
 * 用 MockWebServer 顶替 session-router 的 clear-session 代理：这条链上 admin 只发一次 DELETE，
 * 要证明的是它发对了路径、带上了内部 bearer，以及运行侧那句拒绝原样回到调用方手里。
 *
 * @author agnetix
 * @since 2026-09-23
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRuntimeClientImplTest {

    companion object {
        private const val SERVICE_ID = "admin"
        private const val TEST_SHARED_SECRET = "unit-test-shared-secret-at-least-32-chars"
        private const val SESSION_ID = "web-11111111-2222-3333-4444-555555555555"
    }

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    private fun createService(url: String = baseUrl()): AgentRuntimeClientImpl = AgentRuntimeClientImpl(
        url,
        InternalTokenProvider(SERVICE_ID, TEST_SHARED_SECRET, 300),
    )

    private fun vo(code: Int, message: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":$code,"message":"$message","data":null,"timestamp":1704067200000}""")

    @Test
    @DisplayName("clearSession - 向 router 的会话代理发一次 DELETE")
    fun `clearSession should delete the session through the router`() {
        // Given
        server.enqueue(vo(200, "success"))

        // When
        val result = createService().clearSession(SESSION_ID)

        // Then
        assertEquals(200, result.code)
        val request = server.takeRequest(3, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("DELETE", request!!.method)
        assertEquals("/api/router/agent/session/$SESSION_ID", request.path)
    }

    @Test
    @DisplayName("clearSession - 请求带内部服务 token")
    fun `clearSession should carry the internal bearer`() {
        // Given - router 的 UnifiedAuthFilter 认 typ=internal 的 bearer，也认 API Key，这里走前者
        server.enqueue(vo(200, "success"))

        // When
        createService().clearSession(SESSION_ID)

        // Then
        val authorization = server.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Authorization")
        assertTrue(
            authorization != null && authorization.startsWith("Bearer "),
            "缺少内部服务 token: $authorization",
        )
    }

    @Test
    @DisplayName("clearSession - router 说没绑定实例时按成功处理")
    fun `clearSession should treat an unbound session as released`() {
        // Given - 从未跑过的会话没有任何运行态可回收，router 就是这么答的
        server.enqueue(vo(200, "Session $SESSION_ID is not bound to any instance"))

        // When & Then
        assertEquals(200, createService().clearSession(SESSION_ID).code)
    }

    @Test
    @DisplayName("clearSession - 运行侧的业务拒绝原样带回原因")
    fun `clearSession should relay the runtime refusal with its own reason`() {
        // Given - HTTP 200 + 非 200 code 是 router 失败的固定形状，句子在被删的那份状态里
        server.enqueue(vo(500, "sandbox container is busy"))

        // When
        val result = createService().clearSession(SESSION_ID)

        // Then
        assertEquals(500, result.code)
        assertEquals("sandbox container is busy", result.message)
    }

    @Test
    @DisplayName("clearSession - router 不可达时折成错误而不是抛出异常")
    fun `clearSession should fold an unreachable router into an error`() {
        // Given - 先起后关，拿到的是一个确实没有人再听着的端口
        val dead = MockWebServer()
        dead.start()
        val deadPort = dead.port
        dead.shutdown()

        // When
        val result = createService("http://localhost:$deadPort").clearSession(SESSION_ID)

        // Then - 调用方要靠这个 code 决定"拒绝删除"，抛出去只会让删除变成 500 堆栈
        assertTrue(result.code != 200, "不可达应当是一个业务码，实际: ${result.code}")
        assertTrue(result.message.contains("router", ignoreCase = true), "原因要点明是哪一段断了，实际: ${result.message}")
    }

    @Test
    @DisplayName("clearSession - 空响应体按失败处理")
    fun `clearSession should treat an empty answer as a failure`() {
        // Given - 没有 ResultVo 就等于没人确认释放过
        server.enqueue(MockResponse().setResponseCode(204))

        // When
        val result = createService().clearSession(SESSION_ID)

        // Then
        assertTrue(result.code != 200, "拿不到答复不能算释放成功，实际: ${result.code}")
    }

    @Test
    @DisplayName("clearSession - 会话 id 里的斜杠不会变成第二段路径")
    fun `clearSession keeps a slash inside one path segment`() {
        // Given - 路径是 admin 唯一一次指名要清哪份运行态；一个能加出一段的 id 就等于让它说出别的端点
        server.enqueue(vo(200, "success"))

        // When
        createService().clearSession("web-1/../../admin")

        // Then
        val path = server.takeRequest(3, TimeUnit.SECONDS)!!.path!!
        assertTrue(
            path.startsWith("/api/router/agent/session/web-1%2F") && !path.contains("//"),
            "斜杠必须留在那一段里，实际: $path",
        )
    }
}
