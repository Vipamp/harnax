package com.agnetix.harnax.admin.it

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
import tools.jackson.databind.JsonNode
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The scheduling half of the task endpoints, as the browser calls them: `/api/admin/agent-tasks` and everything below it in,
 * [FakeTaskScheduler] out.
 *
 * Since release 2 these five verbs are nothing but a forward — the state they act on is the scheduler's Quartz
 * store and its own task row — so what is left to test here is the forwarding contract over this service's real
 * security filter, tenant filter and `SchedulerClient` bean: the method, path and query that go out, the
 * identity that goes with them, and the answer that comes back. The scheduling behaviour itself belongs to
 * `harnax-scheduler`'s own suite.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgentTaskSchedulerIT : BaseAdminIT() {

    companion object {
        @JvmStatic
        val fake: FakeTaskScheduler = FakeTaskScheduler()

        @JvmStatic
        val scheduler: MockWebServer = MockWebServer().apply {
            dispatcher = fake
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
        fake.failNext = false
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
        assertNotNull(request, "a request should have reached the scheduler stand-in")
        return request
    }

    /**
     * A task the way a caller gets one: created through this service, which resolves the agent name out of its
     * own `agent` table and hands the row over to the other service.
     */
    private fun ensureTask(): Long {
        if (taskId > 0) return taskId
        assertOk(postJson("/api/admin/agents", agentCreateBody(agentName)))
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

    /** The whole `ResultVo` shell, which is what a relay has to leave intact — [assertOk] hands back `data`. */
    private fun post(path: String): JsonNode = postJson(path)

    @Test
    @Order(1)
    fun `start task is forwarded to the task surface`() {
        val id = ensureTask()
        // Creating the task is itself a forward, and only this first case pays for it: drop its request so the
        // one taken below is the start under test.
        drainRecordedRequests()
        val answer = post("/api/admin/agent-tasks/$id/start")
        assertOk(answer)

        val request = takeSchedulerRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/scheduler/agent-tasks/$taskId/start", request.path)
        // The answer is the shell, with no payload: the older scheduling surface puts "Task started" in `data`,
        // and a client of this API has never seen that string.
        assertTrue(answer.path("data").isNull, "a forwarded start must not grow a data payload: $answer")
    }

    @Test
    @Order(2)
    fun `pause task is forwarded to the task surface`() {
        assertOk(post("/api/admin/agent-tasks/${ensureTask()}/pause"))

        val request = takeSchedulerRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/scheduler/agent-tasks/$taskId/pause", request.path)
        assertEquals(0, fake.taskStatusOf(taskId), "the forwarded pause is what moved the schedule state")
    }

    @Test
    @Order(3)
    fun `trigger task is forwarded to the task surface`() {
        assertOk(post("/api/admin/agent-tasks/${ensureTask()}/trigger"))

        val request = takeSchedulerRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/scheduler/agent-tasks/$taskId/trigger", request.path)
    }

    /**
     * The creator-only gate on a log id moved with `agent_task_log`: this service no longer reads a row before
     * forwarding, so a log the caller does not own is refused by the other service and the refusal is relayed —
     * which is why this case no longer seeds anything into a database.
     */
    @Test
    @Order(4)
    fun `stop is forwarded by log id and an unowned one is the other service refusal`() {
        val logId = 777L
        fake.seedOwnedLog(logId)

        assertOk(post("/api/admin/agent-tasks/logs/$logId/stop"))
        val request = takeSchedulerRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/scheduler/agent-tasks/logs/$logId/stop", request.path)

        val refused = post("/api/admin/agent-tasks/logs/424242/stop")
        assertErr(refused)
        assertEquals("Agent task log not found", refused.path("message").asText())
        assertEquals("/api/scheduler/agent-tasks/logs/424242/stop", takeSchedulerRequest().path)
    }

    @Test
    @Order(5)
    fun `toggle forwards the status switch to the task surface`() {
        assertOk(post("/api/admin/agent-tasks/toggle/${ensureTask()}?status=1"))
        assertEquals("/api/scheduler/agent-tasks/toggle/$taskId?status=1", takeSchedulerRequest().path)
        assertEquals(1, fake.taskStatusOf(taskId), "status=1 goes out as the query parameter the switch sends")

        assertOk(post("/api/admin/agent-tasks/toggle/$taskId?status=0"))
        assertEquals("/api/scheduler/agent-tasks/toggle/$taskId?status=0", takeSchedulerRequest().path)
        assertEquals(0, fake.taskStatusOf(taskId))
    }

    /**
     * This case used to assert the opposite, and had to change direction rather than disappear: `toggle` used to
     * read `agent_task` here and refuse an unknown id without a call. That table moved to the other service, so
     * the visibility gate moved with it and an unknown id now has to reach it. Unchanged is the answer the
     * caller sees — "Agent task not found" — which is the half of the claim the client depends on.
     */
    @Test
    @Order(6)
    fun `toggle of an unknown task is the scheduler not-found answer`() {
        val answer = post("/api/admin/agent-tasks/toggle/99999999?status=1")
        assertErr(answer)

        assertEquals("/api/scheduler/agent-tasks/toggle/99999999?status=1", takeSchedulerRequest().path)
        assertEquals("Agent task not found", answer.path("message").asText())
        assertEquals(400, answer.path("code").asInt())
    }

    @Test
    @Order(7)
    fun `a failure the scheduler answers is propagated to the caller`() {
        ensureTask()
        fake.failNext = true

        val start = post("/api/admin/agent-tasks/$taskId/start")
        assertErr(start)
        assertEquals(500, start.path("code").asInt())
        assertEquals("scheduler boom", start.path("message").asText())

        val toggle = post("/api/admin/agent-tasks/toggle/$taskId?status=1")
        assertErr(toggle)
        assertEquals("scheduler boom", toggle.path("message").asText())
    }

    /**
     * A reload used to be admin's job after a committed write, over HTTP, with a 40902 when it did not take. The
     * reconcile is that service's own after-commit step now, so a forwarded update has to be exactly one call.
     */
    @Test
    @Order(8)
    fun `update task is one forwarded call and notifies no reload`() {
        assertOk(putJson("/api/admin/agent-tasks/${ensureTask()}", mapOf("prompt" to "Updated by scheduler IT")))

        val request = takeSchedulerRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/scheduler/agent-tasks/$taskId", request.path)
        assertTrue(fake.otherPaths.isEmpty(), "no second call after a write: ${fake.otherPaths}")
    }

    @Test
    @Order(9)
    fun `delete task is one forwarded call and cleans up`() {
        assertOk(deleteJson("/api/admin/agent-tasks/${ensureTask()}"))

        val request = takeSchedulerRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/scheduler/agent-tasks/$taskId", request.path)
        assertTrue(fake.otherPaths.isEmpty(), "the reload notify is gone: ${fake.otherPaths}")

        assertErr(getJson("/api/admin/agent-tasks/$taskId"))

        // Cleanup prerequisite agent
        assertOk(deleteJson("/api/admin/agents/$agentId"))
    }
}
