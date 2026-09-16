package com.agnetix.harnax.admin.service.impl

import com.agnetix.harnax.admin.context.TenantContext
import com.agnetix.harnax.auth.InternalTokenProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.function.ThrowingSupplier
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.ObjectMapper
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * SchedulerClientImpl 单元测试
 * 使用 MockWebServer 模拟 harnax-scheduler 的 HTTP 端点,
 * 覆盖单点转发（逗号分隔的多地址配置也只取第一个）、非200、服务不可达等分支
 *
 * @author agnetix
 * @since 2026-06-28
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerClientImplTest {

    companion object {
        private const val SERVICE_ID = "admin"
        private const val TEST_SHARED_SECRET = "unit-test-shared-secret-at-least-32-chars"
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
        // Both are thread-locals the C4 assertions set, and surefire reuses the JVM: leaving them behind
        // would hand the next test class an authenticated user it never asked for.
        SecurityContextHolder.clearContext()
        TenantContext.clear()
    }

    private fun baseUrl(s: MockWebServer = server): String = s.url("/").toString().removeSuffix("/")

    private fun createService(urls: String = baseUrl()): SchedulerClientImpl = SchedulerClientImpl(urls, InternalTokenProvider(SERVICE_ID, TEST_SHARED_SECRET, 300))

    private fun successResponse(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":200,"message":"success","data":null,"timestamp":1704067200000}""")

    private fun bizErrorResponse(message: String = "task not found"): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":500,"message":"$message","data":null,"timestamp":1704067200000}""")

    @Nested
    @DisplayName("触发任务测试")
    inner class TriggerTaskTests {

        @Test
        @DisplayName("triggerTask - 成功返回200")
        fun `triggerTask should succeed and post to trigger endpoint`() {
            // Given
            server.enqueue(successResponse())

            // When
            val result = createService().triggerTask(1L)

            // Then
            assertEquals(200, result.code)
            assertTrue(result.isSuccess())

            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("POST", request!!.method)
            assertEquals("/api/scheduler/tasks/1/trigger", request.path)
        }

        @Test
        @DisplayName("triggerTask - 请求带内部服务 token")
        fun `triggerTask should carry an internal bearer token`() {
            // Given - scheduler 开启 UnifiedAuthFilter 后，只认 typ=internal 的 bearer
            server.enqueue(successResponse())

            // When
            createService().triggerTask(1L)

            // Then
            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull(request)
            val authorization = request!!.getHeader("Authorization")
            assertTrue(authorization != null && authorization.startsWith("Bearer "), "缺少内部服务 token: $authorization")
            assertEquals(SERVICE_ID, request.getHeader("X-Caller-Id"))
        }

        @Test
        @DisplayName("triggerTask - 多实例时只发送到第一个实例")
        fun `triggerTask should only send to first instance`() {
            // Given
            val server2 = MockWebServer()
            server2.start()
            try {
                server.enqueue(successResponse())
                val service = createService("${baseUrl()},${baseUrl(server2)}")

                // When
                val result = service.triggerTask(2L)

                // Then
                assertEquals(200, result.code)
                assertEquals(1, server.requestCount)
                assertEquals(0, server2.requestCount)
            } finally {
                server2.shutdown()
            }
        }

        @Test
        @DisplayName("triggerTask - 未配置URL返回错误")
        fun `triggerTask should return error when no url configured`() {
            // Given: 只有空白的 URL 配置
            val service = createService("  ,  ")

            // When
            val result = service.triggerTask(1L)

            // Then
            assertEquals(500, result.code)
            assertEquals("No scheduler URL configured", result.message)
        }

        @Test
        @DisplayName("triggerTask - 调度器返回业务失败码原样透传")
        fun `triggerTask should pass through business error code`() {
            // Given
            server.enqueue(bizErrorResponse("task not found"))

            // When
            val result = createService().triggerTask(99L)

            // Then
            assertEquals(500, result.code)
            assertEquals("task not found", result.message)
        }

        @Test
        @DisplayName("triggerTask - HTTP非200状态返回服务不可用错误")
        fun `triggerTask should return unavailable error on http 5xx`() {
            // Given
            server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

            // When
            val result = createService().triggerTask(1L)

            // Then
            assertEquals(500, result.code)
            assertTrue(result.message.startsWith("Scheduler service unavailable"), result.message)
        }

        @Test
        @DisplayName("triggerTask - 连接失败(超时/宕机)返回服务不可用错误")
        fun `triggerTask should return unavailable error when scheduler is down`() {
            // Given
            val deadUrl = baseUrl()
            server.shutdown() // 关闭端口模拟调度器宕机

            // When
            val result = createService(deadUrl).triggerTask(1L)

            // Then
            assertEquals(500, result.code)
            assertTrue(result.message.startsWith("Scheduler service unavailable"), result.message)
        }

        @Test
        @DisplayName("triggerTask - 响应体为空返回无响应错误")
        fun `triggerTask should return error when response body is empty`() {
            // Given
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json"))

            // When
            val result = createService().triggerTask(1L)

            // Then
            assertEquals(500, result.code)
            assertEquals("No response from scheduler", result.message)
        }
    }

    @Nested
    @DisplayName("启动任务测试")
    inner class StartTaskTests {

        @Test
        @DisplayName("startTask - 单实例成功")
        fun `startTask should succeed on single instance`() {
            // Given
            server.enqueue(successResponse())

            // When
            val result = createService().startTask(3L)

            // Then
            assertEquals(200, result.code)
            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("/api/scheduler/tasks/3/start", request!!.path)
        }

        @Test
        @DisplayName("startTask - 多实例配置下只发到第一个实例")
        fun `startTask should only reach the first instance`() {
            // Given
            val server2 = MockWebServer()
            server2.start()
            try {
                server.enqueue(successResponse())
                server2.enqueue(successResponse())
                val service = createService("${baseUrl()},${baseUrl(server2)}")

                // When
                val result = service.startTask(3L)

                // Then: the start writes the shared store, so the second instance has nothing to be told
                assertEquals(200, result.code)
                assertEquals(1, server.requestCount)
                assertEquals(0, server2.requestCount)
            } finally {
                server2.shutdown()
            }
        }

        @Test
        @DisplayName("startTask - 第一个实例失败不会被第二个实例的成功盖掉")
        fun `startTask does not let another instance cover a failure`() {
            // Given: the old anySuccess fold turned this into a 200, which is exactly how a half-synced
            // cluster used to hide from 40902.
            val server2 = MockWebServer()
            server2.start()
            try {
                server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
                server2.enqueue(successResponse())
                val service = createService("${baseUrl()},${baseUrl(server2)}")

                // When
                val result = service.startTask(3L)

                // Then
                assertEquals(500, result.code)
                assertEquals(0, server2.requestCount)
            } finally {
                server2.shutdown()
            }
        }

        @Test
        @DisplayName("startTask - 该实例的失败原因原样透传且不做降级重试")
        fun `startTask forwards the one instance failure`() {
            // Given
            val server2 = MockWebServer()
            server2.start()
            try {
                server.enqueue(bizErrorResponse("boom1"))
                server2.enqueue(successResponse())
                val service = createService("${baseUrl()},${baseUrl(server2)}")

                // When
                val result = service.startTask(3L)

                // Then
                assertEquals(500, result.code)
                assertEquals("boom1", result.message)
                assertEquals(1, server.requestCount)
                assertEquals(0, server2.requestCount)
            } finally {
                server2.shutdown()
            }
        }

        @Test
        @DisplayName("startTask - 单实例失败返回服务不可用错误")
        fun `startTask should return unavailable error when single instance is down`() {
            // Given
            val deadUrl = baseUrl()
            server.shutdown()

            // When
            val result = createService(deadUrl).startTask(3L)

            // Then
            assertEquals(500, result.code)
            assertTrue(result.message.startsWith("Scheduler service unavailable"), result.message)
        }
    }

    @Nested
    @DisplayName("暂停任务测试")
    inner class PauseTaskTests {

        @Test
        @DisplayName("pauseTask - 成功发送到pause端点")
        fun `pauseTask should post to pause endpoint`() {
            // Given
            server.enqueue(successResponse())

            // When
            val result = createService().pauseTask(4L)

            // Then
            assertEquals(200, result.code)
            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("/api/scheduler/tasks/4/pause", request!!.path)
        }

        @Test
        @DisplayName("pauseTask - 调度器返回业务失败码原样透传")
        fun `pauseTask should pass through business error`() {
            // Given
            server.enqueue(bizErrorResponse("pause failed"))

            // When
            val result = createService().pauseTask(4L)

            // Then
            assertEquals(500, result.code)
            assertEquals("pause failed", result.message)
        }

        @Test
        @DisplayName("pauseTask - 服务不可达返回错误")
        fun `pauseTask should return error when scheduler is down`() {
            // Given
            val deadUrl = baseUrl()
            server.shutdown()

            // When
            val result = createService(deadUrl).pauseTask(4L)

            // Then
            assertEquals(500, result.code)
            assertTrue(result.message.startsWith("Scheduler service unavailable"), result.message)
        }
    }

    @Nested
    @DisplayName("重载任务测试")
    inner class ReloadTasksTests {

        @Test
        @DisplayName("reloadTasks - 成功发送到reload端点")
        fun `reloadTasks should post to reload endpoint`() {
            // Given
            server.enqueue(successResponse())

            // When
            val result = createService().reloadTasks()

            // Then
            assertEquals(200, result.code)
            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("/api/scheduler/reload", request!!.path)
        }

        @Test
        @DisplayName("reloadTasks - 共享 store 下一次 reload 只打一个实例")
        fun `a reload goes to one instance because the store is shared`() {
            // Two instances used to need two calls: each held its own in-memory schedule. With a JDBC store
            // any node's write lands in the store every node reads, so a broadcast would only multiply the
            // number of places the same reconcile can fail.
            val server2 = MockWebServer()
            server2.start()
            try {
                server.enqueue(successResponse())
                server2.enqueue(successResponse())
                val response = createService("${baseUrl()},${baseUrl(server2)}").reloadTasks()

                // Then
                assertTrue(response.isSuccess())
                assertEquals(1, server.requestCount)
                assertEquals(0, server2.requestCount)
                assertEquals("/api/scheduler/reload", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
            } finally {
                server2.shutdown()
            }
        }

        @Test
        @DisplayName("reloadTasks - 服务不可达返回错误")
        fun `reloadTasks should return error when scheduler is down`() {
            // Given
            val deadUrl = baseUrl()
            server.shutdown()

            // When
            val result = createService(deadUrl).reloadTasks()

            // Then
            assertEquals(500, result.code)
            assertTrue(result.message.startsWith("Scheduler service unavailable"), result.message)
        }
    }

    @Nested
    @DisplayName("停止任务测试")
    inner class StopTaskTests {

        @Test
        @DisplayName("stopTask - 成功发送到stop端点(按日志ID)")
        fun `stopTask should post to stop endpoint by log id`() {
            // Given
            server.enqueue(successResponse())

            // When
            val result = createService().stopTask(77L)

            // Then
            assertEquals(200, result.code)
            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("/api/scheduler/tasks/logs/77/stop", request!!.path)
        }

        @Test
        @DisplayName("stopTask - 多实例配置下也只发一次，避免同一停止被服务两遍")
        fun `a stop reaches one instance because a serviced stop must not be replayed`() {
            // The second node to receive the same stop would see "nothing live", write the 4 -> 5 close-out
            // itself and take the result away from the thread that owns the execution.
            val server2 = MockWebServer()
            server2.start()
            try {
                server.enqueue(successResponse())
                server2.enqueue(successResponse())
                val service = createService("${baseUrl()},${baseUrl(server2)}")

                // When
                val result = service.stopTask(77L)

                // Then
                assertEquals(200, result.code)
                assertEquals(1, server.requestCount)
                assertEquals(0, server2.requestCount)
            } finally {
                server2.shutdown()
            }
        }

        @Test
        @DisplayName("stopTask - 调度器返回业务失败码原样透传")
        fun `stopTask should pass through business error`() {
            // Given
            server.enqueue(bizErrorResponse("log not running"))

            // When
            val result = createService().stopTask(77L)

            // Then
            assertEquals(500, result.code)
            assertEquals("log not running", result.message)
        }

        @Test
        @DisplayName("stopTask - 服务不可达返回错误")
        fun `stopTask should return error when scheduler is down`() {
            // Given
            val deadUrl = baseUrl()
            server.shutdown()

            // When
            val result = createService(deadUrl).stopTask(77L)

            // Then
            assertEquals(500, result.code)
            assertTrue(result.message.startsWith("Scheduler service unavailable"), result.message)
        }
    }

    @Nested
    @DisplayName("任务属主查询测试(契约 C5)")
    inner class TaskOwnerTests {

        /**
         * The one answer this client must never turn into an exception: its caller sits on a cold path
         * inside somebody's task execution, where an owner it cannot resolve is one OAuth tool missing
         * and not a failed run.
         */
        @Test
        @DisplayName("taskOwner - GET 属主端点、带上内部服务 token、解出三值")
        fun `taskOwner should get the owner endpoint with an internal bearer`() {
            // Given
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"code":200,"message":"success","data":{"creator":"bob","tenantId":5,"agentId":9},"timestamp":1704067200000}"""),
            )

            // When
            val result = createService().taskOwner(7L)

            // Then
            assertEquals("bob", result.data?.creator)
            assertEquals(5L, result.data?.tenantId)
            assertEquals(9L, result.data?.agentId)

            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("GET", request!!.method)
            assertEquals("/api/scheduler/agent-tasks/7/owner", request.path)
            val authorization = request.getHeader("Authorization")
            assertTrue(authorization != null && authorization.startsWith("Bearer "), "缺少内部服务 token: $authorization")
        }

        @Test
        @DisplayName("taskOwner - 任务不存在时是 200 且 data 为 null，不是错误")
        fun `taskOwner should keep a missing task as a successful null data`() {
            // Given: scheduler's not-found answer for C5, same shape as its internal lookups.
            server.enqueue(successResponse())

            // When
            val result = createService().taskOwner(4242L)

            // Then
            assertTrue(result.isSuccess(), "code=${result.code} message=${result.message}")
            assertNull(result.data)
        }

        @Test
        @DisplayName("taskOwner - 业务失败码原样透传给属主解析")
        fun `taskOwner should pass through a business error code`() {
            // Given
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"code":40903,"message":"Scheduling is disabled on this instance","data":null,"timestamp":1704067200000}"""),
            )

            // When
            val result = createService().taskOwner(7L)

            // Then
            assertEquals(40903, result.code)
            assertNull(result.data)
        }

        @Test
        @DisplayName("taskOwner - HTTP非200不抛异常，返回服务不可用")
        fun `taskOwner should fold an http failure into an error result`() {
            // Given
            server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))

            // When
            val result = assertDoesNotThrow(ThrowingSupplier { createService().taskOwner(7L) })

            // Then
            assertEquals(500, result.code)
            assertTrue(result.message.startsWith("Scheduler service unavailable"), result.message)
        }

        @Test
        @DisplayName("taskOwner - 调度器宕机时同样返回错误结果而不是抛出")
        fun `taskOwner should fold an unreachable scheduler into an error result`() {
            // Given
            val deadUrl = baseUrl()
            server.shutdown()

            // When
            val result = assertDoesNotThrow(ThrowingSupplier { createService(deadUrl).taskOwner(7L) })

            // Then
            assertEquals(500, result.code)
            assertTrue(result.message.startsWith("Scheduler service unavailable"), result.message)
        }
    }

    @Nested
    @DisplayName("泛化转发测试(release 2 的定时任务面)")
    inner class Forwarding {

        /**
         * The one method every task call now goes through, so the seven cases below are the whole of what this
         * module still does with that domain: carry the request out as it came, bring the answer back as it was.
         */
        @Test
        @DisplayName("forward - GET 的方法、路径与查询参数原样出站")
        fun `forward should carry the method path and query to the scheduler`() {
            server.enqueue(successResponse())

            createService().forward(
                HttpMethod.GET,
                "/api/scheduler/agent-tasks/page",
                mapOf("name" to "Daily", "agentId" to "100", "pageNum" to "2", "pageSize" to "20"),
            )

            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("GET", request!!.method)
            assertEquals(
                "/api/scheduler/agent-tasks/page?name=Daily&agentId=100&pageNum=2&pageSize=20",
                request.path,
                "names, order and values all still the caller's",
            )
        }

        @Test
        @DisplayName("forward - 为空的过滤条件不发出去（缺失与空串在另一端不是一回事）")
        fun `forward should leave an absent query parameter off the wire`() {
            server.enqueue(successResponse())

            createService().forward(
                HttpMethod.GET,
                "/api/scheduler/agent-tasks/page",
                mapOf("name" to null, "agentId" to null, "pageNum" to "1", "pageSize" to "10"),
            )

            assertEquals(
                "/api/scheduler/agent-tasks/page?pageNum=1&pageSize=10",
                server.takeRequest(3, TimeUnit.SECONDS)!!.path,
                "an empty filter would reach the other side as a filter that matches nothing",
            )
        }

        @Test
        @DisplayName("forward - 带空格与冒号的时间范围参数完成转义")
        fun `forward should encode a query value that carries spaces and colons`() {
            server.enqueue(successResponse())

            createService().forward(
                HttpMethod.GET,
                "/api/scheduler/agent-tasks/7/logs",
                mapOf("startTimeFrom" to "2026-07-01 00:00:00", "keyword" to "error rate"),
            )

            val path = server.takeRequest(3, TimeUnit.SECONDS)!!.path!!
            assertFalse(path.contains(' '), "an unencoded space is an illegal URI, not a filter")
            assertEquals(
                listOf("startTimeFrom" to "2026-07-01 00:00:00", "keyword" to "error rate"),
                path.substringAfter('?').split('&').map {
                    URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to
                        URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
                },
                "the scheduler binds the value the log screen sent",
            )
        }

        @Test
        @DisplayName("forward - 请求体按 JSON 出站，不要求本模块拥有任何任务 DTO")
        fun `forward should send a body as json without owning a DTO for it`() {
            server.enqueue(successResponse())

            createService().forward(
                HttpMethod.POST,
                "/api/scheduler/agent-tasks",
                body = mapOf("name" to "Daily", "agentId" to 100, "agentName" to "News Agent"),
            )

            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertTrue(
                request.getHeader("Content-Type")?.startsWith("application/json") == true,
                "the other service binds a typed DTO from this body",
            )
            val sent = ObjectMapper().readTree(request.body.readUtf8())
            assertEquals("News Agent", sent.path("agentName").asText())
            assertEquals(100, sent.path("agentId").asInt())
        }

        @Test
        @DisplayName("forward - 响应树原样带回，含一个存在但为 null 的字段")
        fun `forward should relay the answer tree including a present-but-null field`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"code":200,"message":"success","data":{"total":1,"records":[{"id":7,"taskStatus":1,""" +
                            """"lastRunStatus":null,"lastRunTime":null}]},"timestamp":1704067200000}""",
                    ),
            )

            val result = createService().forward(HttpMethod.GET, "/api/scheduler/agent-tasks/page")

            assertEquals(200, result.code)
            val record = result.data!!.path("records").get(0)
            assertTrue(record.has("lastRunStatus"), "a null the client reads as never-run must survive")
            assertTrue(record.path("lastRunStatus").isNull, "and stay null rather than becoming a default")
            assertEquals(7L, record.path("id").asLong())
        }

        @Test
        @DisplayName("forward - 非 200 业务码与文案原样透传（40902 靠它才可区分）")
        fun `forward should pass the business code and message through unchanged`() {
            val body = """{"code":40902,"message":"Task saved, but the scheduler did not reload: read timed out.""" +
                """ The previous definition stays scheduled until a reconcile round converges it.",""" +
                """"data":null,"timestamp":1704067200000}"""
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(body),
            )

            val result = createService().forward(HttpMethod.DELETE, "/api/scheduler/agent-tasks/7")

            assertEquals(40902, result.code)
            assertEquals(
                "Task saved, but the scheduler did not reload: read timed out. " +
                    "The previous definition stays scheduled until a reconcile round converges it.",
                result.message,
                "40902 has to stay distinguishable from a lost edit, in the same words",
            )
        }

        @Test
        @DisplayName("forward - 对端以 HTTP 400 回答时读的仍是它的响应体，不是传输错误")
        fun `forward should relay a non-2xx answer body instead of folding it`() {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"code":400,"message":"prompt: Prompt is required","data":null,"timestamp":1704067200000}"""),
            )

            val result = createService().forward(HttpMethod.POST, "/api/scheduler/agent-tasks", body = mapOf("name" to "x"))

            // Bean validation answers 400, and the field-colon-message text is what the webui shows verbatim.
            assertEquals(400, result.code)
            assertEquals("prompt: Prompt is required", result.message)
        }

        @Test
        @DisplayName("forward - 服务不可达折叠成错误结果而不是抛出")
        fun `forward should fold an unreachable scheduler into an error result`() {
            val deadUrl = baseUrl()
            server.shutdown()

            val result = assertDoesNotThrow(ThrowingSupplier { createService(deadUrl).forward(HttpMethod.GET, "/api/scheduler/agent-tasks/7") })

            assertEquals(500, result.code)
            assertTrue(result.message.startsWith("Scheduler service unavailable"), result.message)
        }

        @Test
        @DisplayName("forward - 空响应体折叠成无响应错误")
        fun `forward should fold an empty body into the no-response error`() {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json"))

            val result = createService().forward(HttpMethod.GET, "/api/scheduler/agent-tasks/7")

            assertEquals(500, result.code)
            assertEquals("No response from scheduler", result.message)
        }
    }

    @Nested
    @DisplayName("身份转发头测试(契约 C4)")
    inner class IdentityHeaders {

        /**
         * The scheduler verifies *services*, so the person a call is about has to travel in these two headers
         * or the CRUD surface task 6 builds has nothing to own the row to. `X-Forwarded-Tenant` is the one that
         * must never appear: a browser can set it on a request to admin itself, so trusting it would let a
         * caller pick which tenant a write lands in.
         */
        @Test
        @DisplayName("转发写请求 - 带上 X-Forwarded-User 与 X-Tenant-Id，且从不写 X-Forwarded-Tenant")
        fun `a forwarded write carries the end-user identity`() {
            // Given
            authenticate("alice")
            TenantContext.setTenantId(5L)
            server.enqueue(successResponse())

            // When
            createService().triggerTask(1L)

            // Then
            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull(request)
            assertEquals("alice", request!!.getHeader("X-Forwarded-User"))
            assertEquals("5", request.getHeader("X-Tenant-Id"))
            assertNull(request.getHeader("X-Forwarded-Tenant"), "the forgeable tenant header must never be sent")
        }

        @Test
        @DisplayName("属主查询（C5）- 同样带上两个身份头，转发头规则不看方法")
        fun `the owner read carries the identity too`() {
            // Given: the headers are stamped on the one RestClient every path shares, so an endpoint added
            // later cannot forget them by accident.
            authenticate("bob")
            TenantContext.setTenantId(9L)
            server.enqueue(successResponse())

            // When
            createService().taskOwner(7L)

            // Then
            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("GET", request!!.method)
            assertEquals("bob", request.getHeader("X-Forwarded-User"))
            assertEquals("9", request.getHeader("X-Tenant-Id"))
            assertNull(request.getHeader("X-Forwarded-Tenant"))
        }

        @Test
        @DisplayName("共享密钥的内部调用 - 转发为 SYSTEM 而不是 internal-service")
        fun `an internal-service principal is forwarded as SYSTEM`() {
            // Given: this is the shape of the chain the C5 read runs on — agent-service called admin with the
            // raw internal secret, which JwtAuthenticationFilter records as that marker, not as a username.
            authenticate("internal-service")
            server.enqueue(successResponse())

            // When
            createService().reloadTasks()

            // Then
            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("SYSTEM", request!!.getHeader("X-Forwarded-User"), "same word UserContextUtil answers")
            assertNull(request.getHeader("X-Tenant-Id"), "no tenant is in play on that chain")
        }

        @Test
        @DisplayName("没有已认证用户 - 两个头都不发，调用照常发出")
        fun `a call with nobody behind it sends neither identity header`() {
            // Given: SecurityContextHolder cleared below; the anonymous principal counts as nobody.
            SecurityContextHolder.getContext().authentication = AnonymousAuthenticationToken(
                "anon",
                "anonymousUser",
                listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")),
            )
            server.enqueue(successResponse())

            // When
            val result = createService().pauseTask(4L)

            // Then: an identity-less forward is still a forward — refusing here would break the cold paths.
            assertEquals(200, result.code)
            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertNull(request!!.getHeader("X-Forwarded-User"))
            assertNull(request.getHeader("X-Tenant-Id"))
            assertNull(request.getHeader("X-Forwarded-Tenant"))
        }

        /**
         * The task domain leaves through [SchedulerClient.forward] alone since release 2, and the scheduler
         * applies its owner-scoped rules to the name in these headers — so the one method that replaced eleven
         * call sites has to stamp them the same way, or every list silently narrows to the public rows.
         */
        @Test
        @DisplayName("转发任务面 - 泛化 forward 同样带上两个身份头")
        fun `a forwarded task call carries the identity headers`() {
            authenticate("carol")
            TenantContext.setTenantId(3L)
            server.enqueue(successResponse())

            createService().forward(
                HttpMethod.GET,
                "/api/scheduler/agent-tasks/page",
                mapOf("pageNum" to "1"),
            )

            val request = server.takeRequest(3, TimeUnit.SECONDS)
            assertEquals("GET", request!!.method)
            assertEquals("carol", request.getHeader("X-Forwarded-User"))
            assertEquals("3", request.getHeader("X-Tenant-Id"))
            assertNull(request.getHeader("X-Forwarded-Tenant"))
        }

        private fun authenticate(name: String) {
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                name,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        }
    }
}
