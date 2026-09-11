package com.agnetix.harnax.admin.service.impl

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
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import java.util.concurrent.TimeUnit

/**
 * SchedulerClientImpl 单元测试
 * 使用 MockWebServer 模拟 harnax-scheduler 的 HTTP 端点,
 * 覆盖单实例/多实例广播、非200、服务不可达等分支
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
        @DisplayName("startTask - 多实例广播全部实例")
        fun `startTask should broadcast to all instances`() {
            // Given
            val server2 = MockWebServer()
            server2.start()
            try {
                server.enqueue(successResponse())
                server2.enqueue(successResponse())
                val service = createService("${baseUrl()},${baseUrl(server2)}")

                // When
                val result = service.startTask(3L)

                // Then
                assertEquals(200, result.code)
                assertEquals(1, server.requestCount)
                assertEquals(1, server2.requestCount)
            } finally {
                server2.shutdown()
            }
        }

        @Test
        @DisplayName("startTask - 部分实例失败只要有一个成功即成功")
        fun `startTask should succeed when at least one instance succeeds`() {
            // Given
            val server2 = MockWebServer()
            server2.start()
            try {
                server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
                server2.enqueue(successResponse())
                val service = createService("${baseUrl()},${baseUrl(server2)}")

                // When
                val result = service.startTask(3L)

                // Then
                assertEquals(200, result.code)
            } finally {
                server2.shutdown()
            }
        }

        @Test
        @DisplayName("startTask - 全部实例失败返回最后一个错误")
        fun `startTask should fail when all instances fail`() {
            // Given
            val server2 = MockWebServer()
            server2.start()
            try {
                server.enqueue(MockResponse().setResponseCode(500).setBody("boom1"))
                server2.enqueue(bizErrorResponse("boom2"))
                val service = createService("${baseUrl()},${baseUrl(server2)}")

                // When
                val result = service.startTask(3L)

                // Then
                assertEquals(500, result.code)
                assertEquals("boom2", result.message)
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
        @DisplayName("reloadTasks - 多实例广播且全部收到请求")
        fun `reloadTasks should broadcast reload to all instances`() {
            // Given
            val server2 = MockWebServer()
            server2.start()
            try {
                server.enqueue(successResponse())
                server2.enqueue(successResponse())
                val service = createService("${baseUrl()},${baseUrl(server2)}")

                // When
                val result = service.reloadTasks()

                // Then
                assertEquals(200, result.code)
                assertEquals("/api/scheduler/reload", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
                assertEquals("/api/scheduler/reload", server2.takeRequest(3, TimeUnit.SECONDS)!!.path)
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
}
