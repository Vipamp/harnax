package com.agnetix.harnax.admin.it

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Agent task scheduler proxy regression: /api/admin/agent-tasks
 * start/pause/trigger/toggle/stop endpoints.
 *
 * These endpoints proxy to the external harnax-scheduler service, which is
 * not part of the IT environment. A local okhttp3 MockWebServer stands in for
 * it: harnax.scheduler.url is pointed at the mock via @DynamicPropertySource,
 * and each test controls the stubbed ResultVo response.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgentTaskSchedulerIT : BaseAdminIT() {

    companion object {
        private const val OK_BODY = """{"code":200,"message":"success","data":null}"""
        private const val ERR_BODY = """{"code":500,"message":"scheduler boom","data":null}"""

        /** When true the mock scheduler answers every request with a ResultVo error. */
        @Volatile
        private var failNext: Boolean = false

        @JvmStatic
        val scheduler: MockWebServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = if (failNext) ERR_BODY else OK_BODY
                    return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(body)
                }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun schedulerProperties(registry: DynamicPropertyRegistry) {
            registry.add("harnax.scheduler.url") { "http://localhost:${scheduler.port}" }
        }
    }

    private val suffix = Random.nextInt(100000, 999999)
    private val agentName = "it_sched_agent_$suffix"
    private val taskName = "it_sched_task_$suffix"

    private var agentId: Long = -1
    private var taskId: Long = -1

    @BeforeEach
    fun resetSchedulerStub() {
        failNext = false
        drainRecordedRequests()
    }

    @AfterAll
    fun shutdownScheduler() {
        scheduler.shutdown()
    }

    /** Discard requests recorded by previous tests so path assertions stay isolated. */
    private fun drainRecordedRequests() {
        while (scheduler.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // drop
        }
    }

    private fun takeSchedulerRequest(): RecordedRequest {
        val request = scheduler.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request, "a request should have reached the mock scheduler")
        return request
    }

    private fun ensureTask(): Long {
        if (taskId > 0) return taskId
        assertOk(postJson("/api/admin/agents", mapOf("name" to agentName, "status" to 1)))
        val agent = findInPage("/api/admin/agents/page", "name=$agentName") {
            it["name"]?.asText() == agentName
        }
        assertNotNull(agent, "prerequisite agent should exist")
        agentId = agent["id"].asLong()

        val body = mapOf(
            "name" to taskName,
            "agentId" to agentId,
            "prompt" to "IT scheduler proxy",
            "cronExpression" to "0 0 8 * * ?",
        )
        assertOk(postJson("/api/admin/agent-tasks", body))
        val task = findInPage("/api/admin/agent-tasks/page", "name=$taskName") {
            it["name"]?.asText() == taskName
        }
        assertNotNull(task, "prerequisite task should exist")
        taskId = task["id"].asLong()
        return taskId
    }

    @Test
    @Order(1)
    fun `start task proxies to scheduler start endpoint`() {
        assertOk(postJson("/api/admin/agent-tasks/${ensureTask()}/start"))

        val request = takeSchedulerRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/scheduler/tasks/$taskId/start", request.path)
    }

    @Test
    @Order(2)
    fun `pause task proxies to scheduler pause endpoint`() {
        assertOk(postJson("/api/admin/agent-tasks/${ensureTask()}/pause"))

        val request = takeSchedulerRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/scheduler/tasks/$taskId/pause", request.path)
    }

    @Test
    @Order(3)
    fun `trigger task proxies to scheduler trigger endpoint`() {
        assertOk(postJson("/api/admin/agent-tasks/${ensureTask()}/trigger"))

        val request = takeSchedulerRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/scheduler/tasks/$taskId/trigger", request.path)
    }

    @Test
    @Order(4)
    fun `stop task log proxies to scheduler stop endpoint`() {
        assertOk(postJson("/api/admin/agent-tasks/logs/424242/stop"))

        val request = takeSchedulerRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/scheduler/tasks/logs/424242/stop", request.path)
    }

    @Test
    @Order(5)
    fun `toggle task on and off proxies to start and pause`() {
        assertOk(postJson("/api/admin/agent-tasks/toggle/${ensureTask()}?status=1"))
        assertEquals("/api/scheduler/tasks/$taskId/start", takeSchedulerRequest().path)

        assertOk(postJson("/api/admin/agent-tasks/toggle/$taskId?status=0"))
        assertEquals("/api/scheduler/tasks/$taskId/pause", takeSchedulerRequest().path)
    }

    @Test
    @Order(6)
    fun `toggle unknown task fails without calling scheduler`() {
        assertErr(postJson("/api/admin/agent-tasks/toggle/99999999?status=1"))
        assertTrue(
            scheduler.takeRequest(100, TimeUnit.MILLISECONDS) == null,
            "unknown task should be rejected before any scheduler call",
        )
    }

    @Test
    @Order(7)
    fun `scheduler error result is propagated to caller`() {
        failNext = true
        assertErr(postJson("/api/admin/agent-tasks/${ensureTask()}/start"))
        assertErr(postJson("/api/admin/agent-tasks/toggle/$taskId?status=1"))
    }

    @Test
    @Order(8)
    fun `update task notifies scheduler reload`() {
        assertOk(putJson("/api/admin/agent-tasks/${ensureTask()}", mapOf("prompt" to "Updated by scheduler IT")))

        val request = takeSchedulerRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/scheduler/reload", request.path)
    }

    @Test
    @Order(9)
    fun `delete task notifies scheduler reload and cleans up`() {
        assertOk(deleteJson("/api/admin/agent-tasks/${ensureTask()}"))
        assertEquals("/api/scheduler/reload", takeSchedulerRequest().path)

        assertErr(getJson("/api/admin/agent-tasks/$taskId"))

        // Cleanup prerequisite agent
        assertOk(deleteJson("/api/admin/agents/$agentId"))
    }
}
