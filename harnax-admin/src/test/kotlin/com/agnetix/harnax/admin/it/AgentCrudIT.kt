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
    fun `create agent without name returns 400`() {
        assertErr(postJson("/api/admin/agents", mapOf("name" to "", "description" to "no name")))
    }

    @Test
    @Order(8)
    fun `delete agent then detail returns empty`() {
        assertOk(deleteJson("/api/admin/agents/${locateAgentId()}"))

        val node = getJson("/api/admin/agents/$agentId")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull, "deleted agent should not be returned")

        val record = findInPage("/api/admin/agents/page", "name=$agentName") {
            it["name"]?.asText() == agentName
        }
        assertTrue(record == null, "deleted agent should not appear in page result")
    }
}
