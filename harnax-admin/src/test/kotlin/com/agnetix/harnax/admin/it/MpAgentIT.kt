package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mobile agent browsing regression: /api/admin/mp/agents
 *
 * Tokens come from a real mp login (/api/admin/mp/auth/login requires no
 * captcha, same approach as MpAuthUserIT). Complements MpSessionFlowIT with
 * the availability branches: disabled agents are hidden from the list and
 * their detail endpoint is rejected, and sessionCount tracks mp sessions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MpAgentIT : BaseAdminIT() {

    /** SHA-256("admin123") — frontend sends SHA-256(password), DB stores BCrypt of it. */
    private val adminPasswordSha256 = "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9"

    private val suffix = Random.nextInt(100000, 999999)
    private val agentName = "it_mpagent_$suffix"
    private val sessionName = "it_mpagent_session_$suffix"

    private var mpToken: String = ""
    private var agentId: Long = -1
    private var mpSessionId: Long = -1

    private fun mpLoginToken(): String {
        if (mpToken.isNotEmpty()) return mpToken
        val body = mapOf("username" to "admin", "password" to adminPasswordSha256)
        val data = assertOk(parseBody(exchange(HttpMethod.POST, "/api/admin/mp/auth/login", body, token = null)))
        mpToken = data["accessToken"].asText()
        assertTrue(mpToken.isNotBlank(), "mp login should return an access token")
        return mpToken
    }

    private fun mpGet(path: String): JsonNode = parseBody(exchange(HttpMethod.GET, path, null, mpLoginToken()))

    private fun ensureAgent(): Long {
        if (agentId > 0) return agentId
        assertOk(postJson("/api/admin/agents", mapOf("name" to agentName, "description" to "IT mp agent", "status" to 1)))
        val record = findInPage("/api/admin/agents/page", "name=$agentName") {
            it["name"]?.asText() == agentName
        }
        assertNotNull(record, "prerequisite agent should exist")
        agentId = record["id"].asLong()
        return agentId
    }

    @Test
    @Order(1)
    fun `mp agent list requires authentication`() {
        val response = exchange(HttpMethod.GET, "/api/admin/mp/agents", token = null)
        assertEquals(401, response.statusCode.value())
    }

    @Test
    @Order(2)
    fun `mp agent list contains enabled agent with zero session count`() {
        ensureAgent()
        val data = assertOk(mpGet("/api/admin/mp/agents"))
        assertTrue(data.isArray)
        val match = data.firstOrNull { it["id"].asLong() == agentId }
        assertNotNull(match, "enabled agent should be visible in mp agent list")
        assertEquals(agentName, match["name"].asText())
        assertEquals("IT mp agent", match["description"].asText())
        assertEquals(1, match["status"].asInt())
        assertEquals(0, match["sessionCount"].asInt())
    }

    @Test
    @Order(3)
    fun `mp agent detail returns agent with empty mcp and skill lists`() {
        val data = assertOk(mpGet("/api/admin/mp/agents/${ensureAgent()}"))
        assertEquals(agentId, data["id"].asLong())
        assertEquals(agentName, data["name"].asText())
        assertTrue(data["mcpList"].isArray)
        assertEquals(0, data["mcpList"].size())
        assertTrue(data["skillList"].isArray)
        assertEquals(0, data["skillList"].size())
    }

    @Test
    @Order(4)
    fun `mp agent detail of unknown agent fails`() {
        assertErr(mpGet("/api/admin/mp/agents/99999999"))
    }

    @Test
    @Order(5)
    fun `session count reflects created mp session`() {
        val body = mapOf("sessionName" to sessionName, "agentId" to ensureAgent())
        val created = assertOk(parseBody(exchange(HttpMethod.POST, "/api/admin/mp/sessions", body, mpLoginToken())))
        mpSessionId = created["id"].asLong()
        assertTrue(mpSessionId > 0)

        val data = assertOk(mpGet("/api/admin/mp/agents"))
        val match = data.firstOrNull { it["id"].asLong() == agentId }
        assertNotNull(match, "agent should still be listed")
        assertEquals(1, match["sessionCount"].asInt(), "sessionCount should count the new mp session")
    }

    @Test
    @Order(6)
    fun `disabled agent is hidden from list and detail is rejected`() {
        assertOk(putJson("/api/admin/agents/toggle/${ensureAgent()}?status=0"))

        val data = assertOk(mpGet("/api/admin/mp/agents"))
        assertTrue(data.none { it["id"].asLong() == agentId }, "disabled agent should not be listed")

        assertErr(mpGet("/api/admin/mp/agents/$agentId"))

        // Re-enable for cleanup symmetry
        assertOk(putJson("/api/admin/agents/toggle/$agentId?status=1"))
    }

    @Test
    @Order(7)
    fun `cleanup delete mp session and agent`() {
        assertOk(parseBody(exchange(HttpMethod.DELETE, "/api/admin/mp/sessions/$mpSessionId", null, mpLoginToken())))
        assertOk(deleteJson("/api/admin/agents/${ensureAgent()}"))

        val data = assertOk(mpGet("/api/admin/mp/agents"))
        assertTrue(data.none { it["id"].asLong() == agentId }, "deleted agent should not be listed")
    }
}
