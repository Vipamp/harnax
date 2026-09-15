package com.agnetix.harnax.admin.it

import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The task endpoints as a browser uses them, end to end: `/api/admin/agent-tasks` and everything below it in, a forwarded call to
 * [FakeTaskScheduler] out, and the answer handed back unchanged.
 *
 * Release 2 moved the domain, so this class is no longer a database regression for `agent_task` — that table is
 * in the scheduler's own test suite now ([com.agnetix.harnax.scheduler] `AgentTaskOwnerScopeIT`). What it
 * still covers, and nothing else here does, is the whole request chain of this service on those paths: the
 * security filter that has to accept the JWT, the tenant filter, the real `SchedulerClient` bean with its
 * internal bearer and identity headers, and the JSON converter that has to hand back the relayed tree without
 * touching it. The agent half of the flow stays genuinely database-backed, because `agent` is admin's own
 * domain and the agent name the forwarded create has to carry comes out of that table.
 *
 * The assertions that used to prove the moved rules (a duplicate name, an invalid cron, an unresolvable agent)
 * are kept on purpose: they pin that the refusal the scheduler answers still reaches the caller with the same
 * code and the same sentence, which is the client-visible half of those rules.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgentTaskCrudIT : BaseAdminIT() {

    companion object {
        @JvmStatic
        val scheduler: MockWebServer = MockWebServer().apply {
            dispatcher = FakeTaskScheduler()
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun schedulerProperties(registry: DynamicPropertyRegistry) {
            registry.add("harnax.scheduler.url") { "http://localhost:${scheduler.port}" }
        }
    }

    @AfterAll
    fun shutdownScheduler() {
        scheduler.shutdown()
    }

    private val suffix = Random.nextInt(100000, 999999)
    private val agentName = "it_task_agent_$suffix"
    private val taskName = "it_task_$suffix"

    private var agentId: Long = -1
    private var taskId: Long = -1

    private fun ensureAgent(): Long {
        if (agentId > 0) return agentId
        assertOk(postJson("/api/admin/agents", mapOf("name" to agentName, "status" to 1)))
        val record = findInPage("/api/admin/agents/page", "name=$agentName") {
            it["name"]?.asText() == agentName
        }
        assertNotNull(record, "prerequisite agent should exist")
        agentId = record["id"].asLong()
        return agentId
    }

    private fun locateTaskId(): Long {
        if (taskId > 0) return taskId
        val record = findInPage("/api/admin/agent-tasks/page", "name=$taskName") {
            it["name"]?.asText() == taskName
        }
        assertNotNull(record, "created task should be found in page result")
        taskId = record["id"].asLong()
        return taskId
    }

    @Test
    @Order(1)
    fun `create agent task succeeds`() {
        val body = mapOf(
            "name" to taskName,
            "agentId" to ensureAgent(),
            "prompt" to "Say hello every morning",
            "cronExpression" to "0 0 8 * * ?",
            "timeoutSeconds" to 120,
            "description" to "IT task",
        )
        assertOk(postJson("/api/admin/agent-tasks", body))
    }

    @Test
    @Order(2)
    fun `page query finds created task`() {
        assertTrue(locateTaskId() > 0)
    }

    /**
     * The record on the way back is the one the other service made, and `agentName` is the proof that admin
     * resolved and stamped it: that column is a snapshot of this service's `agent` table, which did not move.
     */
    @Test
    @Order(3)
    fun `get detail returns created task paused by default`() {
        val data = assertOk(getJson("/api/admin/agent-tasks/${locateTaskId()}"))
        assertEquals(taskName, data["name"].asText())
        assertEquals(agentId, data["agentId"].asLong())
        assertEquals(agentName, data["agentName"].asText())
        assertEquals("0 0 8 * * ?", data["cronExpression"].asText())
        assertEquals(0, data["taskStatus"].asInt(), "new task should be paused by default")
    }

    @Test
    @Order(4)
    fun `create task with duplicate name fails`() {
        val body = mapOf(
            "name" to taskName,
            "agentId" to ensureAgent(),
            "prompt" to "dup",
            "cronExpression" to "0 0 8 * * ?",
        )
        assertErr(postJson("/api/admin/agent-tasks", body))
    }

    @Test
    @Order(5)
    fun `create task with invalid cron fails`() {
        val body = mapOf(
            "name" to "it_task_badcron_$suffix",
            "agentId" to ensureAgent(),
            "prompt" to "bad cron",
            "cronExpression" to "not-a-cron",
        )
        assertErr(postJson("/api/admin/agent-tasks", body))
    }

    /**
     * Unresolvable here means unresolvable in this service's own `agent` table, and the forward then carries no
     * name at all — which is the case the moved create refuses with "Agent not found".
     */
    @Test
    @Order(6)
    fun `create task with nonexistent agent fails`() {
        val body = mapOf(
            "name" to "it_task_noagent_$suffix",
            "agentId" to 999999999L,
            "prompt" to "no agent",
            "cronExpression" to "0 0 8 * * ?",
        )
        assertErr(postJson("/api/admin/agent-tasks", body))
    }

    @Test
    @Order(7)
    fun `create task without required fields returns 400`() {
        assertErr(postJson("/api/admin/agent-tasks", mapOf("name" to "", "agentId" to ensureAgent(), "prompt" to "x", "cronExpression" to "0 0 8 * * ?")))
        assertErr(postJson("/api/admin/agent-tasks", mapOf("name" to "it_task_noprompt_$suffix", "agentId" to agentId, "prompt" to "", "cronExpression" to "0 0 8 * * ?")))
    }

    @Test
    @Order(8)
    fun `update task changes prompt and cron`() {
        val body = mapOf(
            "prompt" to "Updated prompt",
            "cronExpression" to "0 30 9 * * ?",
            "timeoutSeconds" to 600,
        )
        assertOk(putJson("/api/admin/agent-tasks/${locateTaskId()}", body))

        val data = assertOk(getJson("/api/admin/agent-tasks/$taskId"))
        assertEquals("Updated prompt", data["prompt"].asText())
        assertEquals("0 30 9 * * ?", data["cronExpression"].asText())
        assertEquals(600, data["timeoutSeconds"].asInt())
    }

    /**
     * The rename case the handoff named: moving a task to another agent has to carry that agent's name, because
     * the other service cannot look one up. A legal rename answered with "Agent not found" is what this fails.
     */
    @Test
    @Order(9)
    fun `update task to another agent carries the new name`() {
        assertOk(postJson("/api/admin/agents", mapOf("name" to "${agentName}_2", "status" to 1)))
        val second = findInPage("/api/admin/agents/page", "name=${agentName}_2") {
            it["name"]?.asText() == "${agentName}_2"
        }
        assertNotNull(second, "second agent should exist")

        assertOk(putJson("/api/admin/agent-tasks/${locateTaskId()}", mapOf("agentId" to second["id"].asLong())))
        assertEquals("${agentName}_2", (scheduler.dispatcher as FakeTaskScheduler).agentNameOf(taskId))
    }

    @Test
    @Order(10)
    fun `task logs are empty for new task`() {
        val data = assertOk(getJson("/api/admin/agent-tasks/${locateTaskId()}/logs"))
        assertEquals(0, data["total"].asLong())
    }

    @Test
    @Order(11)
    fun `available agents list contains prerequisite agent`() {
        val data = assertOk(getJson("/api/admin/agent-tasks/agents"))
        assertTrue(data.isArray)
        assertTrue(data.any { it["id"]?.asLong() == agentId }, "active agent should be listed")
    }

    @Test
    @Order(12)
    fun `delete task then detail returns error`() {
        assertOk(deleteJson("/api/admin/agent-tasks/${locateTaskId()}"))

        assertErr(getJson("/api/admin/agent-tasks/$taskId"))

        val record = findInPage("/api/admin/agent-tasks/page", "name=$taskName") {
            it["name"]?.asText() == taskName
        }
        assertTrue(record == null, "deleted task should not appear in page result")

        // Cleanup prerequisite agents
        assertOk(deleteJson("/api/admin/agents/$agentId"))
    }
}
