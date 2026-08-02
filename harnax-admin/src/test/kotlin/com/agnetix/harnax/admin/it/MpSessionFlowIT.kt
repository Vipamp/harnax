package com.agnetix.harnax.admin.it

import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpMethod
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mobile agent browsing, session and chat history regression:
 * /api/admin/mp/agents, /api/admin/mp/sessions, /api/admin/mp/chat
 *
 * All calls run as admin (userId=1). A dedicated agent is created via the
 * admin API and cleaned up at the end.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MpSessionFlowIT : BaseAdminIT() {

    private val suffix = Random.nextInt(100000, 999999)
    private val agentName = "it_mp_agent_$suffix"
    private val sessionName = "it_mp_session_$suffix"

    private var agentId: Long = -1
    private var mpSessionId: Long = -1
    private var routerSessionId: String = ""

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

    @Test
    @Order(1)
    fun `mp agent list contains created agent`() {
        ensureAgent()
        val data = assertOk(getJson("/api/admin/mp/agents"))
        assertTrue(data.isArray)
        val match = data.firstOrNull { it["id"].asLong() == agentId }
        assertNotNull(match, "created agent should be visible in mp agent list")
        assertEquals(agentName, match["name"].asText())
    }

    @Test
    @Order(2)
    fun `mp agent detail returns agent info`() {
        val data = assertOk(getJson("/api/admin/mp/agents/${ensureAgent()}"))
        assertEquals(agentId, data["id"].asLong())
        assertEquals(agentName, data["name"].asText())
    }

    @Test
    @Order(3)
    fun `mp agent detail of unknown agent fails`() {
        assertErr(getJson("/api/admin/mp/agents/99999999"))
    }

    @Test
    @Order(4)
    fun `create mp session binds agent and gets mp- prefixed router session`() {
        val body = mapOf("sessionName" to sessionName, "agentId" to ensureAgent())
        val data = assertOk(postJson("/api/admin/mp/sessions", body))

        mpSessionId = data["id"].asLong()
        routerSessionId = data["routerSessionId"].asText()
        assertTrue(mpSessionId > 0)
        assertTrue(routerSessionId.startsWith("mp-"), "router session id should have mp- prefix: $routerSessionId")
        assertEquals(agentId, data["agentId"].asLong())
        assertEquals(agentName, data["agentName"].asText())
        assertEquals(0, data["messageCount"].asInt())

        // The backing router session row exists too
        val info = assertOk(getJson("/api/admin/sessions/$routerSessionId/config"))
        assertTrue(info["id"].asLong() > 0)
    }

    @Test
    @Order(5)
    fun `create mp session with unknown agent fails`() {
        assertErr(postJson("/api/admin/mp/sessions", mapOf("sessionName" to "bad_$suffix", "agentId" to 99999999)))
    }

    @Test
    @Order(6)
    fun `create mp session without name returns 400`() {
        assertErr(postJson("/api/admin/mp/sessions", mapOf("sessionName" to "", "agentId" to ensureAgent())))
    }

    @Test
    @Order(7)
    fun `mp session list contains created session`() {
        val data = assertOk(getJson("/api/admin/mp/sessions"))
        assertTrue(data.isArray)
        val match = data.firstOrNull { it["id"].asLong() == mpSessionId }
        assertNotNull(match, "created mp session should be listed")
        assertEquals(sessionName, match["sessionName"].asText())
    }

    @Test
    @Order(8)
    fun `update mp session renames it`() {
        val data = assertOk(putJson("/api/admin/mp/sessions/$mpSessionId", mapOf("sessionName" to "$sessionName-upd")))
        assertEquals("$sessionName-upd", data["sessionName"].asText())
    }

    @Test
    @Order(9)
    fun `update unknown mp session fails`() {
        assertErr(putJson("/api/admin/mp/sessions/99999999", mapOf("sessionName" to "nope")))
    }

    @Test
    @Order(10)
    fun `save and read chat history`() {
        val messages = listOf(
            mapOf("role" to "user", "content" to "Hello from IT", "segmentsJson" to "[]"),
            mapOf("role" to "assistant", "content" to "Hi, admin!", "segmentsJson" to "[]"),
        )
        assertOk(postJson("/api/admin/mp/chat/history/$mpSessionId", messages))

        val history = assertOk(getJson("/api/admin/mp/chat/history/$mpSessionId"))
        assertTrue(history.isArray)
        assertEquals(2, history.size())
        assertEquals("user", history[0]["role"].asText())
        assertEquals("Hello from IT", history[0]["content"].asText())
        assertEquals("assistant", history[1]["role"].asText())

        // Message count reflected in session list
        val sessions = assertOk(getJson("/api/admin/mp/sessions"))
        val match = sessions.firstOrNull { it["id"].asLong() == mpSessionId }
        assertNotNull(match)
        assertEquals(2, match["messageCount"].asInt())
    }

    @Test
    @Order(11)
    fun `chat history of foreign session is rejected`() {
        // A token of a different user must not read admin's session history
        val otherToken = jwtUtil.generateToken(99999999L, "it_ghost_$suffix", 1L, 0)
        val node = parseBody(
            exchange(HttpMethod.GET, "/api/admin/mp/chat/history/$mpSessionId", null, otherToken),
        )
        assertErr(node)
    }

    @Test
    @Order(12)
    fun `delete chat history clears messages`() {
        assertOk(deleteJson("/api/admin/mp/chat/history/$mpSessionId"))
        val history = assertOk(getJson("/api/admin/mp/chat/history/$mpSessionId"))
        assertEquals(0, history.size())
    }

    @Test
    @Order(13)
    fun `delete mp session removes it and its router session`() {
        assertOk(deleteJson("/api/admin/mp/sessions/$mpSessionId"))

        val sessions = assertOk(getJson("/api/admin/mp/sessions"))
        assertTrue(sessions.none { it["id"].asLong() == mpSessionId }, "deleted mp session should not be listed")

        // Backing router session is gone as well (config lookup returns data=null)
        val node = getJson("/api/admin/sessions/$routerSessionId/config")
        assertEquals(200, node["code"].asInt())
        assertTrue(node["data"] == null || node["data"].isNull, "router session should be deleted")
    }

    @Test
    @Order(14)
    fun `cleanup delete agent`() {
        assertOk(deleteJson("/api/admin/agents/${ensureAgent()}"))
    }
}
