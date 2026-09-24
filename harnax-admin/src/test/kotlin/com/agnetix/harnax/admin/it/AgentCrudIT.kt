package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Agent CRUD regression: /api/admin/agents
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgentCrudIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val agentName = "it_agent_$suffix"

    private var agentId: Long = -1

    private fun locateAgentId(): Long {
        if (agentId > 0) return agentId
        val record = findInPage("/api/admin/agents/page", "name=$agentName") {
            it["name"]?.asText() == agentName
        }
        assertNotNull(record, "created agent should be found in page result")
        agentId = record["id"].asLong()
        return agentId
    }

    @Test
    @Order(1)
    fun `create agent succeeds`() {
        val body = mapOf(
            "name" to agentName,
            "description" to "IT agent",
            "systemPrompt" to "You are a helpful assistant.",
            "status" to 1,
            "isPublic" to 0,
            "modelId" to ensureAgentModelId(),
        )
        assertOk(postJson("/api/admin/agents", body))
    }

    @Test
    @Order(2)
    fun `page query finds created agent`() {
        assertTrue(locateAgentId() > 0)
    }

    @Test
    @Order(3)
    fun `get detail returns created agent`() {
        val data = assertOk(getJson("/api/admin/agents/${locateAgentId()}"))
        assertEquals(agentName, data["name"].asText())
        assertEquals("IT agent", data["description"].asText())
        assertEquals("You are a helpful assistant.", data["systemPrompt"].asText())
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(4)
    fun `update agent changes description and prompt`() {
        val body = mapOf(
            "name" to agentName,
            "description" to "IT agent updated",
            "systemPrompt" to "Updated prompt.",
        )
        assertOk(putJson("/api/admin/agents/update/${locateAgentId()}", body))

        val data = assertOk(getJson("/api/admin/agents/${locateAgentId()}"))
        assertEquals("IT agent updated", data["description"].asText())
        assertEquals("Updated prompt.", data["systemPrompt"].asText())
    }

    @Test
    @Order(5)
    fun `toggle agent status off and on`() {
        assertOk(putJson("/api/admin/agents/toggle/${locateAgentId()}?status=0"))
        var data = assertOk(getJson("/api/admin/agents/${locateAgentId()}"))
        assertEquals(0, data["status"].asInt())

        assertOk(putJson("/api/admin/agents/toggle/${locateAgentId()}?status=1"))
        data = assertOk(getJson("/api/admin/agents/${locateAgentId()}"))
        assertEquals(1, data["status"].asInt())
    }

    @Test
    @Order(6)
    fun `related sessions of fresh agent is empty`() {
        val data = assertOk(getJson("/api/admin/agents/${locateAgentId()}/related-sessions"))
        assertTrue(data.isArray)
        assertEquals(0, data.size())
    }

    @Test
    @Order(7)
    fun `a create missing a required field is refused by name`() {
        val base = agentCreateBody("it_agent_missing_$suffix")
        // Every row is refused, so nothing here needs cleaning up — and a create that did land would
        // fail the assertErr below.
        val reasons = mapOf(
            "name" to "Agent name cannot be empty",
            "description" to "Agent description cannot be empty",
            "systemPrompt" to "System prompt cannot be empty",
            "modelId" to "Chat model ID cannot be empty",
        )
        reasons.forEach { (field, reason) ->
            val node = postJson("/api/admin/agents", base - field)
            assertErr(node)
            val message = node["message"].asText()
            // AGENT-20: the service force-unwrapped all four, so an omitted one surfaced as
            // 500 "Failed to create agent: null" — a crash that named neither the field nor the fix.
            assertTrue(message.startsWith("$field: "), "the refusal should name $field, got: $message")
            assertTrue(message.contains(reason), "the refusal should carry the reason, got: $message")
        }
    }

    @Test
    @Order(8)
    fun `an agent that owns a session cannot be deleted until the session goes`() {
        val holderName = "it_agent_holder_$suffix"
        val title = "it_agent_holder_session_$suffix"
        assertOk(postJson("/api/admin/agents", agentCreateBody(holderName)))
        val holderId = findInPage("/api/admin/agents/page", "name=$holderName") {
            it["name"]?.asText() == holderName
        }?.get("id")?.asLong() ?: error("prerequisite agent should exist")
        assertOk(postJson("/api/admin/sessions", mapOf("title" to title, "agentId" to holderId)))

        // AGENT-03: the run-time resolves its agent by id, so deleting the row here would leave the
        // session failing on its next message instead of reporting what still points at the agent.
        val refused = deleteJson("/api/admin/agents/$holderId")
        assertErr(refused)
        val message = refused["message"].asText()
        assertTrue(message.contains("session(s)"), "the refusal should count the sessions, got: $message")
        assertTrue(message.contains("1 session(s)"), "the refusal should say how many, got: $message")

        val sessionId = findInPage("/api/admin/sessions/page", "keyword=$title") {
            it["title"]?.asText() == title
        }?.get("id")?.asLong() ?: error("prerequisite session should exist")
        assertOk(deleteJson("/api/admin/sessions/$sessionId"))
        assertOk(deleteJson("/api/admin/agents/$holderId"))
    }

    @Test
    @Order(9)
    fun `delete agent then detail reports not found`() {
        assertOk(deleteJson("/api/admin/agents/${locateAgentId()}"))

        val node = getJson("/api/admin/agents/$agentId")
        assertEquals(404, node["code"].asInt(), "a deleted agent should not be returned")
        assertTrue(node["data"] == null || node["data"].isNull)

        val record = findInPage("/api/admin/agents/page", "name=$agentName") {
            it["name"]?.asText() == agentName
        }
        assertTrue(record == null, "deleted agent should not appear in page result")
    }
}
